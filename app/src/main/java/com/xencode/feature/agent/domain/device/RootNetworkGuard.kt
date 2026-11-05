package com.xencode.feature.agent.domain.device

import android.content.Context
import com.xencode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ROOT "fake foreground": uses root to keep the system treating this app as a foreground
 * process so it stops suspending its networking. Only effective when the device has ROOT; silently skipped otherwise.
 *
 * ## Why this is necessary
 *
 * After backgrounding, SSE silently stalls. In practice there are **two independent layers** of restrictions; fixing only one is not enough:
 *
 * 1. **cgroup v2 freezer**: threads are suspended wholesale, stuck on socket `read()` — no progress and
 *    no exception either (so no error in the logs); the instant it thaws, the backlog of RSTs bursts out as
 *    `SocketException: Software caused connection abort`. Two independent connections can die in the same millisecond, because it is a process-level event, not a remote timeout.
 *
 *    Note that cgroup freezing is **hierarchically inherited**: a UID-level freeze=1 freezes all descendant
 *    processes, and a process-level file showing 0 is useless — looking only at the process level yields the wrong "not frozen" conclusion.
 *    Also, while frozen `/proc/<pid>/status` still shows `S (sleeping)` (`TASK_FROZEN` is just
 *    `TASK_INTERRUPTIBLE` plus a flag bit), so process state cannot be used to detect freezing.
 *
 * 2. **ROM-private background network control**: ColorOS (OPPO/OnePlus) has a `hans` native daemon
 *    that writes background UIDs into netd's eBPF maps (`map_oplus-netd_app_drop_wlan_socket_uid_limit_map`
 *    etc.) and drops packets right at the socket layer. In this state iptables has **not a single rule** — checking the firewall
 *    is pointless; it manifests as `UnknownHostException` (DNS packets dropped), a different problem from freezing.
 *
 * Neither has anything to do with foreground services, `PARTIAL_WAKE_LOCK`, `WifiLock`, or `netpolicy` allowlists —
 * those protect CPU/priority/background data quotas and cannot help with "threads not running" or "sockets dropping packets".
 *
 * ## Countermeasure: keep maintaining it, not a one-shot setting
 *
 * The system keeps reverting the state, so a one-shot setting survives only seconds. Here a persistent root
 * guard loop maintains it (a marker file ensures a single instance; it exits on its own once this app's process is gone):
 *
 * - High frequency (every 2s): unfreeze the UID-level plus all process-level `cgroup.freeze`;
 * - Low frequency (every 10s): `standby bucket = active`, background-run AppOps allow;
 * - Every check: `stop hans` (only where that service exists, i.e. ColorOS);
 * - One-shot: disable global `app_standby_enabled`, add the `netpolicy` background-data allowlist.
 *
 * Deliberately **not** requesting the Doze allowlist: with the screen off there is no work to do anyway; this only targets suspended networking.
 *
 * The guard's stdout/stderr must be redirected to `/dev/null`: the caller merges stderr into
 * stdout and reads to EOF; a background process holding the pipe would make `waitFor()` block for the full timeout.
 */
@Singleton
class RootNetworkGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRootEngine: DeviceRootEngine
) {
    private val mutex = Mutex()

    @Volatile
    private var lastAppliedAtMs = 0L

    /**
     * Exempt the app's background networking. Must be called on an IO thread (goes through a `su` subprocess).
     *
     * Throttled per [APPLY_INTERVAL_MS] within a session: once applied these settings stay effective,
     * so there is no need to fork su for every message. Failures do not throw; the layer above falls back to the regular keep-alive path.
     *
     * @return Whether already (or just now) applied; false when there is no ROOT.
     */
    suspend fun applyIfRooted(): Boolean {
        // The ROOT probe itself is cached; silently skipped when ROOT is not available
        if (!deviceRootEngine.isProvisioned()) return false

        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastAppliedAtMs < APPLY_INTERVAL_MS) return@withLock true

            val pkg = context.packageName
            // One script, one su: apply the one-shot settings first, then write the guard script and launch it in the background.
            // $pkg is injected by Kotlin; \$xxx are device-shell variables and must stay escaped.
            val script = buildString {
                append("uid=\$(stat -c %u /data/data/$pkg 2>/dev/null); ")
                // ---- One-shot: disable App Standby (which deprioritizes background networking) and add the background-data allowlist ----
                append("settings put global app_standby_enabled 0; ")
                append("[ -n \"\$uid\" ] && cmd netpolicy add restrict-background-whitelist \$uid; ")
                // ---- Unfreeze once right now instead of waiting for the guard's first tick ----
                append("if [ -n \"\$uid\" ]; then for f in /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$uid/pid_*/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/pid_*/cgroup.freeze; ")
                append("do [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null; done; fi; ")
                append("am set-standby-bucket $pkg active; ")
                append("cmd appops set $pkg RUN_IN_BACKGROUND allow; ")
                append("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; ")
                // ---- Stop the ROM-private background network control daemon (ColorOS hans) ----
                append("if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi; ")
                // ---- Write the guard script: the quoted heredoc delimiter prevents early shell expansion ----
                append("cat > $GUARD_PATH <<'XENCODE_GUARD_EOF'\n")
                append("#!/system/bin/sh\n")
                append("u=\$(stat -c %u /data/data/$pkg 2>/dev/null)\n")
                append("op=\$(cat $GUARD_MARKER 2>/dev/null)\n")
                append("if [ -n \"\$op\" ] && [ -d /proc/\$op ]; then exit 0; fi\n")
                append("echo \$\$ > $GUARD_MARKER 2>/dev/null\n")
                append("i=0\n")
                append("while [ \$i -lt $GUARD_MAX_TICKS ]; do\n")
                append("  pidof $pkg >/dev/null 2>&1 || break\n")
                // High frequency: unfreeze (plain echo, negligible cost)
                append("  for f in /sys/fs/cgroup/apps/uid_\$u/cgroup.freeze /sys/fs/cgroup/uid_\$u/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$u/pid_*/cgroup.freeze /sys/fs/cgroup/uid_\$u/pid_*/cgroup.freeze; do\n")
                append("    [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null\n")
                append("  done\n")
                // Every pass: keep the ROM-private control daemon down (it drops packets of background UIDs at the socket layer)
                append("  if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi\n")
                // Low frequency: am/cmd are java commands with heavy fork cost, so run them less often
                append("  if [ \$((i % $STATE_EVERY_TICKS)) -eq 0 ]; then\n")
                append("    am set-standby-bucket $pkg active >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("  fi\n")
                append("  sleep $GUARD_TICK_SECONDS\n")
                append("  i=\$((i+1))\n")
                append("done\n")
                append("rm -f $GUARD_MARKER 2>/dev/null\n")
                append("XENCODE_GUARD_EOF\n")
                append("nohup sh $GUARD_PATH >/dev/null 2>&1 &\n")
                // ---- Read back key state so the log shows at a glance whether it took effect ----
                append("echo \"freeze=\$(cat /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze 2>/dev/null) ")
                append("hans=\$(getprop init.svc.hans) bucket=\$(am get-standby-bucket $pkg)\"")
            }

            val result = runCatching {
                deviceRootEngine.runCommandSyncWithExit(script, null, TIMEOUT_MS)
            }.getOrElse {
                FileLogger.w(TAG, "ROOT network exemption failed: ${it.message}")
                return@withLock false
            }

            if (result.exitCode == null) {
                FileLogger.w(TAG, "ROOT network exemption did not finish: ${result.output.take(200)}")
                return@withLock false
            }
            lastAppliedAtMs = now
            val status = result.output.lineSequence()
                .firstOrNull { it.contains("freeze=") }
                ?.trim()
            FileLogger.i(
                TAG,
                "ROOT fake foreground deployed (exit=$result.exitCode) $status"
            )
            true
        }
    }

    private companion object {
        const val TAG = "RootNetworkGuard"

        /** Timeout for the single script: `cmd netpolicy` occasionally stalls — generous, but not enough to hold up task startup. */
        const val TIMEOUT_MS = 15_000L

        /**
         * Throttle interval: only controls how often the guard is "re-verified and relaunched"; the guard itself keeps maintaining state in between.
         * Must not be too long — the guard can be reaped by the system, and a long interval would leave an unattended gap;
         * the script is idempotent (the marker keeps it single-instance) and takes under 1 second, so re-checking once a minute is cheap enough.
         */
        const val APPLY_INTERVAL_MS = 60 * 1000L

        /** Where the guard script is written on disk. */
        const val GUARD_PATH = "/data/local/tmp/.xencode_guard.sh"

        /** Guard single-instance marker: holds the pid; invalid once the process is gone. */
        const val GUARD_MARKER = "/data/local/tmp/.xencode_guard"

        /** Guard loop interval (seconds): the system re-freezes on a seconds cadence, so 2 seconds is enough to hold it down. */
        const val GUARD_TICK_SECONDS = 2

        /** `am`/`cmd` are java commands with heavy fork cost, so run them only every N ticks. */
        const val STATE_EVERY_TICKS = 5

        /** Maximum ticks the guard stays alive: 2s × 1800 = 60 minutes, enough to cover a long task. */
        const val GUARD_MAX_TICKS = 1800
    }
}
