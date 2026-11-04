package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高危拦截规则的持久化与内置预设。
 *
 * 存于 App 私有目录（`filesDir/aicode/high_risk_rules.json`）——容器内/真机工作区内的 AI 均无法篡改，
 * 契合授权层「规则绝不落盘到工作区」的安全原则（与全局授权规则同位置）。
 *
 * 首次启动写入全部预设规则；预设带版本号，后续版本新增预设会在加载时按 id 增量合并
 * （用户已有的开关状态与修改保留）。用户可增删改、单条开关，也可一键恢复预设。
 */
@Singleton
class HighRiskRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HighRiskRules"
        const val RULES_FILE = "high_risk_rules.json"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    @Serializable
    private data class HighRiskRuleFile(
        val presetVersion: Int = 0,
        val rules: List<HighRiskRule> = emptyList()
    )

    private val file: File
        get() = File(File(context.filesDir, "aicode"), RULES_FILE)

    private val state = MutableStateFlow<List<HighRiskRule>?>(null)
    private val mutex = Mutex()

    /** 规则流，供设置页订阅；首次订阅触发懒加载。 */
    val rulesFlow: Flow<List<HighRiskRule>> = flow {
        ensureLoaded()
        emitAll(state.filterNotNull())
    }

    /** 一次性读取当前规则（权限评估时用）。 */
    suspend fun getRulesOnce(): List<HighRiskRule> {
        ensureLoaded()
        return state.value ?: emptyList()
    }

    /** 新增一条用户规则（id 由调用方生成，建议 `user-<timestamp>`）。 */
    suspend fun addRule(rule: HighRiskRule) = edit { list ->
        if (list.none { it.id == rule.id }) list.add(rule)
    }

    /** 按 id 更新规则（编辑内容/开关）。 */
    suspend fun updateRule(rule: HighRiskRule) = edit { list ->
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule
    }

    suspend fun removeRule(id: String) = edit { list ->
        list.removeAll { it.id == id }
    }

    /** 恢复预设：内置预设全量重置为默认（启用），用户自建规则保留。 */
    suspend fun resetToPresets() = edit { list ->
        list.removeAll { it.builtin }
        list.addAll(HighRiskRulePresets.RULES)
        FileLogger.i(TAG, "已恢复高危拦截规则预设")
    }

    // ── 内部 ──────────────────────────────────────────────────

    private suspend fun ensureLoaded() {
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = withContext(Dispatchers.IO) { loadOrSeed() }
        }
    }

    /** 文件不存在写入预设；存在则按版本增量合并新预设（保留用户改动）。 */
    private fun loadOrSeed(): List<HighRiskRule> {
        if (!file.isFile) {
            val seed = HighRiskRulePresets.RULES
            writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, seed))
            return seed
        }
        val parsed = runCatching {
            JSON.decodeFromString<HighRiskRuleFile>(file.readText())
        }.getOrElse {
            FileLogger.w(TAG, "读取 ${file.path} 失败: ${it.message}，回退预设")
            return HighRiskRulePresets.RULES
        }
        if (parsed.presetVersion >= HighRiskRulePresets.VERSION) return parsed.rules
        // 版本迁移：1) 剔除已废弃预设；2) 调整过 pattern 的预设按新定义覆盖（保留用户自改的开关状态）；
        // 3) 再补入用户列表里不存在的新预设。用户自建规则不受影响。
        val removed = HighRiskRulePresets.REMOVED_IDS
        val updated = parsed.rules
            .filter { it.id !in removed }
            .map { old -> HighRiskRulePresets.UPDATES[old.id]?.copy(enabled = old.enabled) ?: old }
        val existingIds = updated.map { it.id }.toSet()
        val merged = updated + HighRiskRulePresets.RULES.filter { it.id !in existingIds }
        writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, merged))
        return merged
    }

    private suspend fun edit(mutate: (MutableList<HighRiskRule>) -> Unit) {
        ensureLoaded()
        mutex.withLock {
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) {
                writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, list))
            }
            state.value = list
        }
    }

    private fun writeToFile(content: HighRiskRuleFile) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(content))
    }
}

/**
 * 内置预设高危规则（[HighRiskRulePresets.VERSION] 递增触发对存量用户的增量合并）。
 *
 * 收录原则（v2 收紧口径后）：只拦「不可逆、影响系统状态、可外泄数据」三类里**后果重**的操作——
 * 磁盘擦除/格式化、系统开关机与属性、包与权限管理、SIGKILL、防火墙、管道执行、SSH/SCP 外传、
 * Git 历史破坏、公开发布、系统包卸载、账户与定时任务。
 *
 * v2 起**不拦**（日常开发高频、后果可控）：普通 `rm 文件`、`rm -f`、递归以外的一切删除、
 * `start/stop/su/sudo/doas`、`pkill/killall`、`nc/rsync/sftp`、语言包卸载
 * （npm/pip/yarn/pnpm/gem/cargo uninstall）、`git clean/restore/checkout --` 等
 * 工作区级回滚——这些在 RULE_BASED 挡位下改走「单次放行」而非强制拦截。
 */
object HighRiskRulePresets {

    const val VERSION = 3

    private const val CAT_DELETE = "Delete and erase"
    private const val CAT_DISK = "Disk and partition"
    private const val CAT_SYSTEM = "System control"
    private const val CAT_PACKAGE = "Package and permissions"
    private const val CAT_PROCESS = "Process management"
    private const val CAT_NETWORK = "Network and firewall"
    private const val CAT_EXFIL = "Download-execute and exfil"
    private const val CAT_GIT = "Destructive Git"
    private const val CAT_PUBLISH = "Publish and package changes"
    private const val CAT_ACCOUNT = "Accounts and cron"

    private fun p(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.PREFIX, category = category, description = desc)

    private fun r(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.REGEX, category = category, description = desc)

    /**
     * 已从预设中除名、迁移时要从存量用户配置里清掉的 id（v1 → v2）。
     * 这些规则误伤面大或属日常开发高频操作，拦截收益低于打扰成本。
     */
    val REMOVED_IDS: Set<String> = setOf(
        "preset.start", "preset.stop",
        "preset.su", "preset.sudo", "preset.doas",
        "preset.nc", "preset.sftp", "preset.rsync",
        "preset.truncate",
        "preset.killall", "preset.pkill",
        "preset.git-clean", "preset.git-restore", "preset.git-checkout-discard",
        "preset.pip-uninstall", "preset.npm-uninstall", "preset.yarn-remove",
        "preset.pnpm-remove", "preset.gem-uninstall", "preset.cargo-uninstall"
    )

    val RULES: List<HighRiskRule> = listOf(
        r("rm", "^rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s|--recursive(\\s|$))", CAT_DELETE, "Recursively delete directories (rm -r/-R/--recursive)"),
        p("shred", "shred", CAT_DELETE, "Securely erase files; unrecoverable"),

        p("dd", "dd", CAT_DISK, "Raw disk/device image I/O; can destroy data"),
        r("mkfs", "mkfs(\\.[a-z0-9]+)?(\\s|$)", CAT_DISK, "Format a filesystem"),
        p("mkswap", "mkswap", CAT_DISK, "Create a swap partition"),
        p("wipefs", "wipefs", CAT_DISK, "Erase filesystem signatures on a device"),
        p("fdisk", "fdisk", CAT_DISK, "Modify the disk partition table"),
        p("sfdisk", "sfdisk", CAT_DISK, "Scripted partition-table changes"),
        p("parted", "parted", CAT_DISK, "Modify disk partitions"),
        p("blkdiscard", "blkdiscard", CAT_DISK, "Discard all data on a block device"),
        r("dev-write", ">\\s*/dev/(sd|mmcblk|nvme|block)", CAT_DISK, "Redirect writes straight to a block device"),

        p("reboot", "reboot", CAT_SYSTEM, "Reboot the device"),
        p("shutdown", "shutdown", CAT_SYSTEM, "Shut down"),
        p("poweroff", "poweroff", CAT_SYSTEM, "Power off"),
        p("halt", "halt", CAT_SYSTEM, "Halt the system"),
        p("setenforce", "setenforce", CAT_SYSTEM, "Disable SELinux enforcing mode; weakens system security"),
        p("mount", "mount", CAT_SYSTEM, "Mount a filesystem"),
        p("umount", "umount", CAT_SYSTEM, "Unmount a filesystem"),
        p("setprop", "setprop", CAT_SYSTEM, "Change Android system properties"),
        p("svc", "svc", CAT_SYSTEM, "Toggle Wi-Fi / mobile data / power"),
        p("settings-put", "settings put", CAT_SYSTEM, "Change global system settings"),
        p("device-config-put", "device_config put", CAT_SYSTEM, "Change device config parameters"),

        p("pm-uninstall", "pm uninstall", CAT_PACKAGE, "Uninstall an app"),
        p("pm-clear", "pm clear", CAT_PACKAGE, "Clear app data"),
        p("pm-disable", "pm disable", CAT_PACKAGE, "Disable an app"),
        p("pm-grant", "pm grant", CAT_PACKAGE, "Grant an app permission"),
        p("pm-revoke", "pm revoke", CAT_PACKAGE, "Revoke an app permission"),
        p("appops-set", "appops set", CAT_PACKAGE, "Change app ops permissions"),
        r("chmod-recursive", "chmod\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "Recursively change file permissions"),
        r("chown-recursive", "chown\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "Recursively change file ownership"),

        r("kill-9", "kill\\s+(-[a-zA-Z]*9|-SIGKILL)\\b", CAT_PROCESS, "Force-kill a process (SIGKILL)"),

        p("iptables", "iptables", CAT_NETWORK, "Change firewall rules"),
        p("ip6tables", "ip6tables", CAT_NETWORK, "Change IPv6 firewall rules"),
        p("nft", "nft", CAT_NETWORK, "Change nftables firewall"),
        p("tc", "tc", CAT_NETWORK, "Change traffic-control rules"),

        r("pipe-to-shell", "\\|\\s*(sudo\\s+)?(ba|z|fi|da)?sh\\b", CAT_EXFIL, "Pipe downloaded content straight into a shell (curl/wget … | sh)"),
        p("ssh", "ssh", CAT_EXFIL, "Connect to a remote host; can exfiltrate data"),
        p("scp", "scp", CAT_EXFIL, "Copy files to a remote host"),

        r("git-push-force", "git\\s+push\\s+.*(-f\\b|--force)", CAT_GIT, "Force-push; overwrites remote history"),
        p("git-reset-hard", "git reset --hard", CAT_GIT, "Hard reset; discards all working-tree and staged changes"),
        p("git-branch-force-delete", "git branch -D", CAT_GIT, "Force-delete a branch (including unmerged commits)"),
        p("git-filter-branch", "git filter-branch", CAT_GIT, "Rewrite entire commit history"),

        p("npm-publish", "npm publish", CAT_PUBLISH, "Publicly publish an npm package; cannot be undone"),
        p("yarn-publish", "yarn publish", CAT_PUBLISH, "Publicly publish an npm package; cannot be undone"),
        p("pnpm-publish", "pnpm publish", CAT_PUBLISH, "Publicly publish an npm package; cannot be undone"),
        p("cargo-publish", "cargo publish", CAT_PUBLISH, "Publicly publish a crate; cannot be undone"),
        p("gem-push", "gem push", CAT_PUBLISH, "Publicly publish a gem"),
        p("twine-upload", "twine upload", CAT_PUBLISH, "Upload and publish a Python package"),
        p("docker-push", "docker push", CAT_PUBLISH, "Push an image to a remote registry"),
        r("apt-remove", "apt(-get)?\\s+(remove|purge|autoremove)", CAT_PUBLISH, "Uninstall system packages"),
        p("apk-del", "apk del", CAT_PUBLISH, "Uninstall packages"),
        r("dnf-remove", "(dnf|yum)\\s+(remove|erase|autoremove)", CAT_PUBLISH, "Uninstall system packages"),
        p("pacman-remove", "pacman -R", CAT_PUBLISH, "Uninstall packages"),

        p("crontab", "crontab", CAT_ACCOUNT, "Change cron jobs (-r clears them)"),
        p("passwd", "passwd", CAT_ACCOUNT, "Change an account password"),
        p("useradd", "useradd", CAT_ACCOUNT, "Create a system account"),
        p("userdel", "userdel", CAT_ACCOUNT, "Delete a system account"),
        p("usermod", "usermod", CAT_ACCOUNT, "Modify a system account")
    )

    val UPDATES: Map<String, HighRiskRule> = RULES.associateBy { it.id }
}
