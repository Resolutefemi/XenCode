package com.xencode.feature.agent.domain.tool.container

import com.xencode.feature.agent.domain.container.BoundedOutput
import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.agent.domain.container.CommandEvent
import com.xencode.feature.agent.domain.container.DelegatingCommandEngine
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.PendingToolPermission
import com.xencode.feature.agent.domain.tool.StreamingAgentTool
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.agent.domain.tool.ToolStreamEvent
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * Commands run in the currently selected workspace directory, so the AI's shell operations (npm install, git, etc.)
 * act on the same directory as the file tools.
 *
 * Also implements [StreamingAgentTool]: prefers line-by-line streaming output so the chat shows command progress in real time;
 * [execute] is kept as the non-streaming fallback, and both produce the same aggregated final result (identical content fed back to the model).
 */
class ExecuteCommandTool @Inject constructor(
    private val delegatingEngine: DelegatingCommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** Default timeout (seconds), aligned with [LinuxContainerEngine.DEFAULT_TIMEOUT_MS]. */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** Timeout cap (seconds), aligned with [LinuxContainerEngine.MAX_TIMEOUT_MS]. */
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Bash"
    override val description = "Run a one-shot shell command and return output synchronously. Default env follows the user's preferred execution environment. Set env explicitly: \"device\" for host Android (/storage, dumpsys/pm/am); \"container\" for the full Linux toolchain (git/npm/python/gcc/make). Default \"auto\" follows preferences. For long-running or resident tasks (installing deps, starting services), use `terminal` instead of `&`."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Shell command to run",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "Max runtime in seconds; the command is killed on timeout. Default $DEFAULT_TIMEOUT_SECONDS, cap $MAX_TIMEOUT_SECONDS. Raise it for slow commands such as installing dependencies.",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "Elevate retry: if a command was blocked by built-in safety (catastrophic deletion, etc.) and you truly need it, set true to retry; the user is prompted for a one-time grant (not remembered). No effect in PLAN mode.",
            required = false
        ),
        "env" to ToolParameter(
            name = "env",
            type = ParameterType.STRING,
            description = "Execution environment. \"auto\" (default) follows the user's preferred env; \"device\" runs on the host (for /storage, dumpsys/pm/am, device status); \"container\" runs in the local Linux container (git/npm/python/gcc/make and other toolchain work). Use auto day to day; set the other env only when you need it.",
            required = false
        )
    )

    /** Resolves the env parameter to an execution environment; missing or invalid values fall back to AUTO (follows the user's preference). */
    private fun resolveEnv(args: Map<String, JsonElement>): DelegatingCommandEngine.TargetEnv {
        val raw = args["env"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return DelegatingCommandEngine.TargetEnv.AUTO
        return when (raw) {
            "container", "linux", "proot" -> DelegatingCommandEngine.TargetEnv.CONTAINER
            "device", "host", "root" -> DelegatingCommandEngine.TargetEnv.DEVICE
            "remote", "ssh" -> DelegatingCommandEngine.TargetEnv.REMOTE
            else -> DelegatingCommandEngine.TargetEnv.AUTO
        }
    }

    /** Parses the timeout (seconds) parameter, clamps it to the valid range, and returns ms; uses the default when absent. */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "unknown command"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm command",
            summary = command,
            details = "Will run in the current execution environment.\nTimeout: ${timeoutSeconds}s",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing required parameter: command")

        return try {
            val env = resolveEnv(args)
            // All environments pass the host absolute path as the workspace: the container engine binds it to /root/workspace inside the container via -b.
            // Do not call toContainerPath() first here — it returns the literal ~/workspace, which proot cannot resolve on the host,
            // causing the bind to fail and /root/workspace inside the container to become an empty placeholder (persistent terminals use the host absolute path, hence they work).
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            // When an environment is chosen explicitly, check readiness first: give the AI a clear reason if the container is not installed/initialized, instead of letting the command fail obscurely.
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    return ToolResult.Error("Execution environment not ready (${env.name}): $hint")
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command (env=${env.name}, timeout=${timeoutMs}ms): $command")
            val output = engine.runCommandSync(command, workdir, timeoutMs)
            FileLogger.v(TAG, "execute_command done, ${output.length} chars")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command failed: $command", e)
            ToolResult.Error("Command failed: ${e.message}")
        }
    }

    /**
     * Streaming execution: emits [ToolStreamEvent.Progress] line by line, and emits [ToolStreamEvent.Completed] when the command ends.
     * The final result is equivalent to [execute] (also bounded via [BoundedOutput]: oversized output keeps only the head+tail),
     * guaranteeing the content fed back to the model is consistent and cannot blow up the context.
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.xencode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Missing required parameter: command")))
            return@flow
        }

        // Bounded accumulation: the final result fed back to the model keeps only head+tail, preventing oversized output from blowing up the context.
        val accumulated = BoundedOutput()
        try {
            val env = resolveEnv(args)
            // Same as execute: all environments pass the host absolute path; the container engine maps it to /root/workspace internally via -b.
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    emit(ToolStreamEvent.Completed(ToolResult.Error("Execution environment not ready (${env.name}): $hint")))
                    return@flow
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command(stream, env=${env.name}, timeout=${timeoutMs}ms): $command")
            engine.runCommandStream(command, workdir, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text)
                        accumulated.append("\n")
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> { /* exit status is aggregated once the stream completes */ }
                }
            }
            FileLogger.v(TAG, "execute_command(stream) done, ${accumulated.totalChars} chars")
            emit(ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive(accumulated.build()))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fallback: if the underlying flow terminates with an exception, the Progress events already emitted line by line to the user should still be kept as the final result,
            // rather than being overwritten by an empty Error thrown here (otherwise the model would only see "execution failed" and lose all previously shown output).
            FileLogger.e(TAG, "execute_command(stream) exception (kept ${accumulated.totalChars} chars): $command", e)
            val saved = accumulated.build()
            val result = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(saved))
            } else {
                ToolResult.Error("Command failed: ${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }
}
