package com.xencode.feature.agent.domain.shizuku

import android.os.Bundle
import com.xencode.feature.agent.domain.container.BoundedOutput
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService: instantiated by Shizuku in the shell (uid 2000) process, runs shell commands on the app's behalf.
 *
 * This process is not a proper Android app process, so only Java/system-level APIs are available; no Context-dependent capabilities
 * (getContentResolver / registerReceiver etc.), hence the implementation uses only [ProcessBuilder].
 *
 * The class name and constructor are reflectively loaded by Shizuku and must not be obfuscated by R8 (see proguard-rules.pro).
 */
class ShizukuShellService : IShizukuShellService.Stub() {

    /** Destroy hook reserved by Shizuku: triggered by unbindUserService(remove=true), ends this process. */
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            // Output reading must run in parallel with waiting for exit: a full pipe blocks the child process, and waiting before reading deadlocks.
            // Also cap the size: the Binder transaction buffer tops out at 1MB, and oversized output would throw TransactionTooLargeException.
            val output = BoundedOutput()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.append(line)
                            output.append("\n")
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            result.putString(KEY_OUTPUT, output.build())
            result.putInt(KEY_EXIT_CODE, if (finished) process.exitValue() else EXIT_TIMEOUT)
        } catch (e: Exception) {
            result.putString(KEY_OUTPUT, e.message ?: "Execution failed")
            result.putInt(KEY_EXIT_CODE, EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
        return result
    }

    companion object {
        const val KEY_OUTPUT = "output"
        const val KEY_EXIT_CODE = "exitCode"

        /** Exit code for a command timeout (process force-killed). */
        const val EXIT_TIMEOUT = -1000

        /** Exit code for a startup/read failure. */
        const val EXIT_FAILURE = -1

        private const val READER_JOIN_TIMEOUT_MS = 2_000L
    }
}
