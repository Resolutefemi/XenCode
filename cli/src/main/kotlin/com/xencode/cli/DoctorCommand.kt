package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.telemetry.XpMath
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareHead
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * `xencode doctor` — environment self-check.
 *
 * Hard failures (exit 1): unusable config (file missing, no active provider,
 * validation rejected) or an unsupported JVM. Warnings (exit 0): the active
 * provider base URL not answering within 8s, or a workspace without a goal DAG.
 */
class DoctorCommand : XeCommand(
    name = "doctor",
    helpText = "Check the runtime, config, network, workspace DAG and XP ledger"
) {
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        var hardFail = false

        // --- JVM ---
        val javaVersion = System.getProperty("java.version") ?: "?"
        if (javaOk(javaVersion)) {
            echo("[ok] java $javaVersion (${javaMajorVersion(javaVersion)} >= 17)")
        } else {
            echo("[fail] java $javaVersion (needs >= 17)", err = true)
            hardFail = true
        }
        echo("[ok] os ${System.getProperty("os.name") ?: "?"} / ${System.getProperty("os.arch") ?: "?"}")

        // --- Config ---
        val configPath = PcConfigStore.defaultPath()
        val configFile = File(configPath)
        if (!configFile.isFile) {
            echo("[fail] config missing: $configPath (run `xencode init` or `xencode config add`)", err = true)
            hardFail = true
        } else {
            echo("[ok] config $configPath")
            val config = PcConfigStore.load()
            val provider = config.activeProvider()
            if (provider == null) {
                echo("[fail] no active provider (run `xencode config add` then `xencode config use`)", err = true)
                hardFail = true
            } else {
                echo("[ok] active provider: ${provider.name} (${provider.kind}) model=${provider.model} baseUrl=${provider.baseUrl}")
                val rejection = provider.validate()
                if (rejection != null) {
                    echo("[fail] provider config invalid: $rejection", err = true)
                    hardFail = true
                } else {
                    echo("[ok] provider config valid")
                    // Network: warning only, never a hard failure.
                    val net = probeNetwork(provider.baseUrl)
                    if (net == null) {
                        echo("[warn] network: ${provider.baseUrl} unreachable (8s timeout) — offline or blocked", err = true)
                    } else {
                        echo("[ok] network: ${provider.baseUrl} $net")
                    }
                }
            }
            val dw = config.defaultWorkspace
            echo("[info] default workspace: ${dw ?: "(not set; falls back to the current directory)"}")
        }

        // --- Workspace DAG ---
        val dagPath = GoalDagFile.filePathFor(runtime.workspaceRoot)
        val dagFile = File(dagPath)
        if (!dagFile.isFile) {
            echo("[warn] workspace DAG missing: $dagPath (run `xencode goal add <title>`)", err = true)
        } else {
            val dag = GoalDagFile.load(runtime.workspaceRoot)
            echo("[ok] workspace DAG: ${dag.nodes.size} goals · ${dag.doneCount} done · ${dag.progressPercent}%")
        }

        // --- XP ledger (shared database; the legacy JSON ledger is imported on first use) ---
        val dbFile = MachineStores.sharedDbPath()
        if (!java.io.File(dbFile).isFile) {
            echo("[info] shared DB missing: $dbFile (created on your first chat turn or XP award)")
        } else {
            val s = MachineStores.sharedXpStore().summary()
            echo("[ok] xp ${XpMath.summaryLine(s.totalXp, s.streakDays)} · shared DB $dbFile")
        }

        throw ProgramResult(if (hardFail) 1 else 0)
    }

    /**
     * HEAD the provider base URL with an 8s budget. Any HTTP answer counts as
     * reachable (404/405 still prove the endpoint is up); null means unreachable.
     */
    private fun probeNetwork(baseUrl: String): String? {
        val http = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
        }
        return try {
            runBlocking {
                withTimeout(8_500) {
                    val resp: HttpResponse = http.prepareHead(baseUrl).execute()
                    "reachable (HTTP ${resp.status.value})"
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            http.close()
        }
    }
}
