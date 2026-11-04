package com.aicode.feature.agent.domain.tool.shizuku

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 通过 Shizuku 以 adb shell（uid 2000）身份执行命令的工具。
 *
 * 与 [com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool]（本地容器 / 远程 SSH）不同，
 * 本工具直接作用于 Android 系统本身：可执行 `pm` / `am` / `cmd` 等系统命令、读写 `/sdcard` 等。
 * 需用户已安装 Shizuku 并授予本应用权限，否则返回错误提示。
 *
 * UserService 的 AIDL 调用是同步、一次性的（无逐行回传），故不实现流式输出。
 */
class ShizukuTool @Inject constructor(
    private val shizukuManager: ShizukuManager
) : AgentTool() {
    private companion object {
        const val TAG = "ShizukuTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Shizuku"

    override val description =
        "Run a command on the host Android system as adb shell (uid 2000) via Shizuku (same as `adb shell`). Use for `pm`/`am`/`cmd` and /sdcard I/O. " +
            "Requires Shizuku installed and granted; every call shows a confirmation dialog."

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Shell command to run via adb shell",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "Max runtime in seconds; the command is killed on timeout. Default $DEFAULT_TIMEOUT_SECONDS, cap $MAX_TIMEOUT_SECONDS.",
            required = false
        )
    )

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
            title = "Confirm Shizuku command",
            summary = command,
            details = "Will run as adb shell on the Android system.\nTimeout: ${timeoutSeconds}s",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing required parameter: command")

        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            return ToolResult.Error("Shizuku is not ready: ${stateHint(state)}", code = "SHIZUKU_NOT_READY")
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Shizuku exec (timeout=${timeoutMs}ms): $command")
            val result = shizukuManager.runCommand(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Shizuku exec done, ${result.output.length} chars, exit ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku exec failed: $command", e)
            ToolResult.Error("Shizuku command failed: ${e.message}")
        }
    }

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed; install it and start the Shizuku service"
        ShizukuState.NOT_RUNNING -> "Shizuku service is not running; start it in the Shizuku app"
        ShizukuState.PERMISSION_DENIED -> "This app has not been granted Shizuku; grant it in Settings"
        ShizukuState.READY -> ""
    }
}
