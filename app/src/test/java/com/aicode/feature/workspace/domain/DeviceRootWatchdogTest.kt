package com.aicode.feature.workspace.domain

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the interrupt safety of the watchdog thread body.
 *
 * Background: the v1.0.4 on-device crash (DEVICE_ROOT, chat screen, Thread-845,
 * InterruptedException@Thread.sleep). Root cause: the second sleep (the grace period
 * after destroy) was unprotected — the finally-interrupt from the main flow landed
 * after the timeout verdict, during the grace sleep, and the exception escaping run()
 * was escalated by the default uncaught-exception handler, crashing the whole app.
 *
 * Note on assertions: a dead thread also reports isAlive == false, so we must attach an
 * UncaughtExceptionHandler to record escaping exceptions; otherwise the test can be
 * fooled by the old code.
 */
class DeviceRootWatchdogTest {

    /** Long-lived subprocess that works cross-platform: ping on Windows (the timeout command refuses piped stdin), sleep elsewhere. */
    private fun sleeper(): ProcessBuilder =
        if ((System.getProperty("os.name") ?: "").startsWith("Windows")) {
            ProcessBuilder("cmd", "/c", "ping -n 30 127.0.0.1")
        } else {
            ProcessBuilder("sleep", "30")
        }

    /** Watchdog thread + escaped-exception capture; returns Pair(thread, capture slot). */
    private fun watchdog(process: Process, timeoutMs: Long, timedOut: AtomicBoolean): Pair<Thread, AtomicReference<Throwable>> {
        val escaped = AtomicReference<Throwable>()
        val t = Thread({ runShWatchdog(process, timeoutMs, timedOut) }, "watchdog-test")
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped.set(e) }
        t.isDaemon = true
        return t to escaped
    }

    @Test
    fun `interrupt during grace period_watchdog exits cleanly with no escaped exception`() {
        val p = sleeper().start()
        try {
            val timedOut = AtomicBoolean(false)
            val (t, escaped) = watchdog(p, 150, timedOut)
            t.start()
            // past the 150ms first sleep with destroy already run; now inside the 200ms grace sleep
            Thread.sleep(280)
            t.interrupt() // simulate the main flow's finally-interrupt arriving now
            t.join(3000)

            assertNull("no uncaught exception may escape run() (root cause of the v1.0.4 crash)", escaped.get())
            assertFalse("watchdog thread should exit on its own", t.isAlive)
            assertTrue("should have been judged timed out", timedOut.get())
            assertFalse("subprocess should have been destroyed", p.isAlive)
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun `interrupt during the first sleep_exits cleanly with no escaped exception`() {
        val p = sleeper().start()
        try {
            val timedOut = AtomicBoolean(false)
            val (t, escaped) = watchdog(p, 60_000, timedOut)
            t.start()
            Thread.sleep(120)
            t.interrupt()
            t.join(3000)

            assertNull("no uncaught exception may escape run()", escaped.get())
            assertFalse(t.isAlive)
            assertFalse("not timed out; should not be set", timedOut.get())
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun `process already exited_watchdog idles without false timeout`() {
        val p = sleeper().start()
        p.destroyForcibly()
        p.waitFor()
        val timedOut = AtomicBoolean(false)
        val (t, escaped) = watchdog(p, 50, timedOut)
        t.start()
        t.join(3000)
        assertNull("不得有未捕获异常逃逸 run()", escaped.get())
        assertFalse(t.isAlive)
        assertFalse("process dead; should not be judged timed out", timedOut.get())
    }
}
