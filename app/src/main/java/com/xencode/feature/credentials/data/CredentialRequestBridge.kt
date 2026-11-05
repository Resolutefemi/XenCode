package com.xencode.feature.credentials.data

import android.content.Context
import com.xencode.core.util.FileLogger
import com.xencode.core.watch.FileChangeHub
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.credentials.domain.model.GitCredential
import com.xencode.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-IPC bridge between the custom git credential helper (in-container /root/.xencode/git-credential-xencode) and the app.
 *
 * When git inside the container lacks credentials, the helper writes `cred-req-<id>` to /root/.xencode (via the PRoot -b bind, the same
 * inode as the host's filesDir/xencode). This bridge subscribes to [FileChangeHub]'s xencode directory events to capture requests → exposes the [request] StateFlow for a global Compose dialog →
 * after the user fills them in, [respond] writes a `cred-resp-<id>` plaintext KV; the helper polls for it, feeds it back to git, and git resumes automatically. All three surfaces (UI / AI Bash /
 * interactive terminal) share this one chain for missing git credentials, with no per-command path adaptation.
 *
 * Temp file cleanup: the helper deletes the request file itself once it has the response (the helper knows when it's done), and it also deletes the response file it read;
 * the app only writes responses and never deletes files it didn't write, avoiding delete races with the helper. See the rm logic inside the helper script.
 *
 * In-flight counting: on request arrival [LinuxContainerEngine.incPromptInFlight], on response write [decPromptInFlight]; together with
 * [LinuxContainerEngine.launchKillWatchdog] the timeout kill is paused while a helper is in flight — the user can walk away for minutes to fill in credentials and the git command
 * won't be force-killed by the 120s watchdog.
 *
 * Directory watching is delegated to the central service [FileChangeHub] (inotify across PRoot -b is mostly reliable for app filesDir, but on some devices inotify can
 * fail on bound directories), so this bridge also runs a low-frequency fallback polling coroutine ([fallbackPollLoop]) scanning unhandled `cred-req-*` entries; both paths
 * deduplicate by requestId to prevent double handling.
 *
 * Lifecycle: @Singleton = app-level, started via [com.xencode.AIEditorApp] calling [start]. The dialog can pop on any page (terminal/chat/Git),
 * not just the Git page.
 */
@Singleton
class CredentialRequestBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val containerEngine: LinuxContainerEngine,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "CredentialRequestBridge"
        const val REQ_PREFIX = "cred-req-"
        const val RESP_PREFIX = "cred-resp-"
        /** Fallback polling interval: even when the central service's inotify fails, requests are still caught within ~1s. */
        const val FALLBACK_POLL_MS = 1000L
        /** Capacity cap of the seen dedup set; oldest entries are evicted beyond it. */
        const val SEEN_CAP = 64
    }

    /** Pending credential request to show (host comes from the git credential protocol's host field). null means nothing pending. */
    data class CredentialRequest(
        val requestId: String,
        val host: String
    )

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    /** Dedup set of handled/in-flight requestIds, preventing the event subscription and the fallback poll from both firing on the same entry. */
    private val seen = LinkedHashSetWithCap(SEEN_CAP)

    private val seenMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    private var xencodeDir: File? = null

    /**
     * Starts listening: subscribes to the central service's xencode directory events to capture requests, and launches the fallback polling coroutine. Idempotent, callable repeatedly.
     */
    fun start() {
        val dir = File(context.filesDir, "xencode").apply { mkdirs() }
        xencodeDir = dir
        if (!started) {
            started = true
            // Request files land under the ~/.xencode root, watched uniformly by the central service; this bridge only does business-side filtering, dedup, and the dialog.
            scope.launch {
                fileChangeHub.watchXencode().collect { batch ->
                    for (change in batch.changes) {
                        val name = File(change.hostPath).name
                        if (!name.startsWith(REQ_PREFIX) || name.endsWith(".tmp")) continue
                        handleRequest(File(dir, name), name)
                    }
                }
            }
            FileLogger.i(TAG, "Subscribed to xencode directory events, watching ${dir.absolutePath}")
        }
        // Fallback polling: still picks up requests when inotify fails on -b directories on some devices; also cleans up last session's leftovers.
        scope.launch { cleanupStale(dir); fallbackPollLoop(dir) }
    }

    /** Fallback polling: scans cred-req-* files not yet in seen, covering misses by the central service. App-resident, never exits. */
    private suspend fun fallbackPollLoop(dir: File) {
        while (true) {
            delay(FALLBACK_POLL_MS)
            runCatching {
                dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp") }
                    ?.forEach { f -> handleRequest(f, f.name) }
            }
        }
    }

    /** Cleans up last session's leftover cred-req/cred-resp at startup, avoiding spurious dialogs. */
    private fun cleanupStale(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    /** Parses a request file → sets [request] and increments in-flight. Both the event subscription and the poll go through here, deduped via seen. */
    private suspend fun handleRequest(file: File, name: String) {
        val requestId = name.removePrefix(REQ_PREFIX)
        val fresh = seenMutex.withLock { seen.addCapped(requestId) }
        if (!fresh) return   // Already handled, skip
        // The file was deleted by the helper or is empty (startup leftovers were cleaned up; normally unreachable here) — skip.
        if (!file.exists() || file.length() == 0L) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val host = text.lineSequence()
            .firstOrNull { it.startsWith("host=") }
            ?.removePrefix("host=")
            ?.trim()
            ?.lowercase()
            ?: ""
        if (host.isBlank()) return
        containerEngine.incPromptInFlight()
        FileLogger.i(TAG, "Credential request received host=$host id=$requestId")
        _request.value = CredentialRequest(requestId, host)
    }

    /**
     * User finished filling in credentials: writes the response plaintext KV + saves the credentials file (one per host, no re-entry later) + decrements in-flight + clears [request].
     * [host] comes from [CredentialRequest] (git won't delete the req before receiving the resp, but host is already in the request — passing it directly is safer).
     */
    fun respond(requestId: String, host: String, username: String, token: String) {
        val dir = xencodeDir ?: File(context.filesDir, "xencode")
        scope.launch {
            writeRespAtomically(dir, requestId, "username=$username\npassword=$token\n")
            runCatching {
                val h = host.trim().lowercase()
                credentialRepository.save(
                    GitCredential(id = h, host = h, username = username.trim(), token = token)
                )
            }.onFailure { FileLogger.e(TAG, "Failed to save the filled-in credentials", it) }
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, "Credentials filled in id=$requestId host=$host")
        }
    }

    /** User cancels the dialog: writes a cancel response (the helper exits non-zero so git reports an auth failure) + decrements in-flight + clears state. */
    fun cancel(requestId: String) {
        val dir = xencodeDir ?: File(context.filesDir, "xencode")
        scope.launch {
            writeRespAtomically(dir, requestId, "cancel=1\n")
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, "User cancelled the credential dialog id=$requestId")
        }
    }

    private fun clearIfCurrent(requestId: String) {
        if (_request.value?.requestId == requestId) _request.value = null
    }

    /** Atomic write via .tmp-then-rename, so the helper's polling never reads a half-written file. */
    private fun writeRespAtomically(dir: File, requestId: String, content: String) {
        val target = File(dir, RESP_PREFIX + requestId)
        val tmp = File(dir, "$RESP_PREFIX${requestId}.tmp")
        runCatching {
            tmp.writeText(content)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "Failed to write the credential response file", it) }
    }
}

/** A [LinkedHashSet] with a capacity cap; beyond [cap] the oldest entries are evicted automatically. Used by [CredentialRequestBridge.seen] for dedup. */
private class LinkedHashSetWithCap(private val cap: Int) : LinkedHashSet<String>() {
    fun addCapped(id: String): Boolean {
        if (contains(id)) return false
        add(id)
        if (size > cap) {
            iterator().next().let { remove(it) }
        }
        return true
    }
}
