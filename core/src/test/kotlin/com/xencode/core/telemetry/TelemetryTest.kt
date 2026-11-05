package com.xencode.core.telemetry

import com.xencode.core.state.XenEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/** Telemetry: command streaks, best streak, spec radar rendering. */
class TelemetryTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun streaks_incrementAndReset() {
        val t = Telemetry(tmp)
        t.recordCommand("cargo build", 0)
        t.recordCommand("cargo test", 0)
        assertEquals(2, t.load().commandStreak)
        assertEquals(2, t.load().bestStreak)
        t.recordCommand("cargo build", 1)
        assertEquals(0, t.load().commandStreak)
        assertEquals(2, t.load().bestStreak)
        assertEquals(3, t.load().commands)
    }

    @Test
    fun radar_recordsTestLookingCommands() {
        val t = Telemetry(tmp)
        t.recordCommand("cargo build", 0)
        assertEquals(0, t.load().radar.size) // not a test command
        t.recordCommand("./gradlew test", 0)
        t.recordCommand("pytest -q", 1)
        val state = t.load()
        assertEquals(2, state.radar.size)
        assertEquals(1, state.radarPassed)
        assertEquals(1, state.radarFailed)
        val rendered = Telemetry.renderRadar(state)
        assertTrue(rendered.contains("ox"), rendered)
    }

    @Test
    fun radar_capKeepsLastForty() {
        val t = Telemetry(tmp)
        repeat(50) { t.recordTest("t$it", true) }
        assertEquals(40, t.load().radar.size)
    }

    @Test
    fun toolCallAndFileWriteCounters() {
        val t = Telemetry(tmp)
        t.recordToolCall()
        t.recordToolCall()
        t.recordFileWrite()
        val s = t.load()
        assertEquals(2L, s.toolCalls)
        assertEquals(1L, s.filesWritten)
    }

    @Test
    fun verificationEventsEmittedWhenEventsProvided() {
        val log = XenEventLog(tmp)
        Telemetry(tmp, log).recordCommand("cargo check", 0)
        assertTrue(log.latest(5).any { it.contains("VERIFICATION") && it.contains("streak") }, log.latest(5).toString())
    }
}
