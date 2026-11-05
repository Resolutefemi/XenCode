package com.xencode.core.telemetry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xencode.core.state.XenEventLog
import java.io.File

/**
 * Gamified engineering telemetry, persisted at
 * `<workspace>/.xencode/telemetry.json`.
 *
 * - **Command streak**: consecutive successful `run_command` executions
 *   (exit 0). The first failure resets it; the best run is kept.
 * - **Spec radar**: every test-looking command becomes a RED/GREEN entry
 *   (`pass=false` -> RED, `true` -> GREEN), rendered as the live radar grid.
 * - **Tool calls / commands / tokens-approx**: cumulative counters.
 */
class Telemetry(private val workspaceRoot: File, private val events: XenEventLog? = null) {

    data class RadarEntry(val id: String, val passed: Boolean, val at: Long, val label: String)

    data class State(
        val commandStreak: Int = 0,
        val bestStreak: Int = 0,
        val toolCalls: Long = 0,
        val commands: Long = 0,
        val filesWritten: Long = 0,
        val radar: List<RadarEntry> = emptyList()
    ) {
        val radarPassed: Int get() = radar.count { it.passed }
        val radarFailed: Int get() = radar.count { !it.passed }
    }

    private val file: File get() = File(File(workspaceRoot, ".xencode"), "telemetry.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): State = runCatching {
        gson.fromJson(file.readText(Charsets.UTF_8), State::class.java) ?: State()
    }.getOrElse { State() }

    private fun save(state: State) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(state), Charsets.UTF_8)
    }

    fun recordToolCall(): State = mutate { it.copy(toolCalls = it.toolCalls + 1) }

    fun recordFileWrite(): State = mutate { it.copy(filesWritten = it.filesWritten + 1) }

    /**
     * Records one command outcome; updates the streak, and when the command
     * looks like a test run (`test`/`spec` in the command line) also the radar.
     */
    fun recordCommand(command: String, exitCode: Int): State = mutate { s ->
        val ok = exitCode == 0
        val streak = if (ok) s.commandStreak + 1 else 0
        val best = maxOf(s.bestStreak, streak)
        val looksLikeTest = TEST_HINTS.any { command.contains(it, ignoreCase = true) }
        val radar = if (looksLikeTest) {
            (s.radar + RadarEntry("t" + System.currentTimeMillis().toString(36), ok, System.currentTimeMillis(), command.take(80)))
                .takeLast(40)
        } else s.radar
        s.copy(commandStreak = streak, bestStreak = best, commands = s.commands + 1, radar = radar)
    }

    /** Explicit radar entry (used by the .mjs `sync.recordTest` binding). */
    fun recordTest(id: String, passed: Boolean, label: String = ""): State = mutate { s ->
        s.copy(radar = (s.radar + RadarEntry(id, passed, System.currentTimeMillis(), label.take(80))).takeLast(40))
    }

    private fun mutate(f: (State) -> State): State {
        val next = f(load())
        save(next)
        if (next.commandStreak > 0) {
            events?.emit(XenEventLog.Types.VERIFICATION, "streak ${next.commandStreak}", "streak" to next.commandStreak)
        }
        return next
    }

    companion object {
        private val TEST_HINTS = listOf("test", "spec", "pytest", "jest", "vitest", "gradlew test", "cargo test")

        /** The radar grid: 2 rows of glyphs, RED `x` / GREEN `o`, newest last. */
        fun renderRadar(state: State): String {
            if (state.radar.isEmpty()) return "spec radar: (no test runs recorded yet)"
            val glyphs = state.radar.joinToString("") { if (it.passed) "o" else "x" }
            return "spec radar [$glyphs] ${state.radarPassed}o/${state.radarFailed}x"
        }
    }
}
