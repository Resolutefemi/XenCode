package com.xencode.feature.agent.domain.tool.instabox

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.PendingToolPermission
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.settings.data.repository.InstaboxConfig
import com.xencode.feature.settings.data.repository.RemoteEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

class InstaboxTool @Inject constructor(
    private val settings: RemoteEnvironmentSettingsRepository,
    private val client: InstaboxClient
) : AgentTool() {

    override val name = "linux_machine"
    override val description =
        "Persistent InstaBox Linux computer in the cloud (Firecracker VM with /data disk, Debian, desktop+browser on agent-browser). " +
            "Use this when the user asks to run work on a remote computer, when the job is too heavy for the phone/container, " +
            "or when a disposable remote sandbox with immediate results is preferred. Home is /data/home. Reused across turns."
    override val capabilities = setOf(
        ToolCapability.EXECUTE_COMMANDS,
        ToolCapability.NETWORK_WRITE,
        ToolCapability.NETWORK_READ,
        ToolCapability.EXTERNAL_TOOL
    )
    override val permissionPolicy = ToolPermissionPolicy.ASK

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = cleanAction(argString(args, "action"))
        return if (action in READ_ONLY_ACTIONS) setOf(ToolCapability.NETWORK_READ)
        else capabilities
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Sub-tool: create, connect, info, list, kill, pause, resume, stop, set_auto_suspend, " +
                "run_code, run_command, list_processes, kill_process, files_list, files_read, files_write, " +
                "files_mkdir, files_remove, files_exists, download, get_url, unpublish_port, list_ports, install, " +
                "desktop_start, desktop_status, desktop_screenshot, desktop_open, desktop_click, desktop_type, " +
                "desktop_press, desktop_scroll, desktop_move, desktop_stream, desktop_revoke, snapshot, metrics.",
            required = true
        ),
        "machineId" to ToolParameter("machineId", ParameterType.STRING, "Existing InstaBox machine id (m_...).", required = false),
        "sandboxId" to ToolParameter("sandboxId", ParameterType.STRING, "Alias of machineId.", required = false),
        "template" to ToolParameter("template", ParameterType.STRING, "Template when creating: agent-browser (default), universal, ci.", required = false),
        "name" to ToolParameter("name", ParameterType.STRING, "Optional machine name. Lowercase letters, digits, and hyphens.", required = false),
        "autoSuspendMs" to ToolParameter("autoSuspendMs", ParameterType.INTEGER, "Idle pause in milliseconds. Paid plans: 5-30 minutes or 0 to disable.", required = false),
        "code" to ToolParameter("code", ParameterType.STRING, "Source to execute for run_code.", required = false),
        "language" to ToolParameter("language", ParameterType.STRING, "Language for run_code: python, javascript, typescript, bash. Default python.", required = false),
        "command" to ToolParameter("command", ParameterType.STRING, "Shell command for run_command.", required = false),
        "cwd" to ToolParameter("cwd", ParameterType.STRING, "Working directory. Default /data/home.", required = false),
        "background" to ToolParameter("background", ParameterType.BOOLEAN, "If true, start run_command detached and return the pid.", required = false),
        "timeoutMs" to ToolParameter("timeoutMs", ParameterType.INTEGER, "Command timeout in milliseconds. Default 120000, max 600000.", required = false),
        "pid" to ToolParameter("pid", ParameterType.INTEGER, "Process id for kill_process.", required = false),
        "path" to ToolParameter("path", ParameterType.STRING, "Absolute path for filesystem actions. Default /data/home.", required = false),
        "content" to ToolParameter("content", ParameterType.STRING, "File contents for files_write.", required = false),
        "files" to ToolParameter(
            name = "files",
            type = ParameterType.ARRAY,
            description = "Multiple files for files_write. Each item has path and content.",
            required = false,
            itemsSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "content" to mapOf("type" to "string")
                ),
                "required" to listOf("path", "content")
            )
        ),
        "port" to ToolParameter("port", ParameterType.INTEGER, "Machine port to publish with get_url.", required = false),
        "portName" to ToolParameter("portName", ParameterType.STRING, "Display name when publishing a port.", required = false),
        "packages" to ToolParameter(
            name = "packages",
            type = ParameterType.ARRAY,
            description = "Package names for install.",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "manager" to ToolParameter("manager", ParameterType.STRING, "Package manager for install: pip or npm. Default pip.", required = false),
        "url" to ToolParameter("url", ParameterType.STRING, "http(s) URL for desktop_open.", required = false),
        "x" to ToolParameter("x", ParameterType.INTEGER, "X pixel for desktop_click or desktop_move.", required = false),
        "y" to ToolParameter("y", ParameterType.INTEGER, "Y pixel for desktop_click or desktop_move.", required = false),
        "button" to ToolParameter("button", ParameterType.STRING, "Mouse button for desktop_click: left, right, middle.", required = false),
        "double" to ToolParameter("double", ParameterType.BOOLEAN, "Double-click for desktop_click.", required = false),
        "text" to ToolParameter("text", ParameterType.STRING, "Text to type for desktop_type.", required = false),
        "keys" to ToolParameter("keys", ParameterType.STRING, "Key or chord for desktop_press, for example Enter or ctrl+l.", required = false),
        "direction" to ToolParameter("direction", ParameterType.STRING, "Scroll direction: up, down, left, right.", required = false),
        "amount" to ToolParameter("amount", ParameterType.INTEGER, "Scroll notches. Default 3.", required = false),
        "width" to ToolParameter("width", ParameterType.INTEGER, "Desktop width for desktop_start. Default 1280.", required = false),
        "height" to ToolParameter("height", ParameterType.INTEGER, "Desktop height for desktop_start. Default 800.", required = false),
        "viewOnly" to ToolParameter("viewOnly", ParameterType.BOOLEAN, "desktop_stream view-only (default true).", required = false),
        "ttlSeconds" to ToolParameter("ttlSeconds", ParameterType.INTEGER, "desktop_stream link lifetime, 60 to 86400. Default 3600.", required = false),
        "snapshotName" to ToolParameter("snapshotName", ParameterType.STRING, "Name for snapshot.", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = cleanAction(argString(args, "action")) ?: "unknown"
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Remote InstaBox",
            summary = "AI requests linux_machine ($action) on a cloud Linux machine",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val config = settings.getInstaboxConfig()
        if (!config.enabled) {
            return ToolResult.Error(
                "InstaBox is disabled. Enable it in Settings → Remote Environments → InstaBox.",
                "DISABLED"
            )
        }
        if (config.apiKey.isBlank()) {
            return ToolResult.Error(
                "InstaBox API key is not configured. Save your key in Settings → Remote Environments → InstaBox.",
                "NO_KEY"
            )
        }
        val rawAction = argString(args, "action")
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        val action = cleanAction(rawAction)
            ?: return ToolResult.Error(
                "Unknown linux_machine action: $rawAction. Valid: ${ACTIONS.joinToString(", ")}",
                "UNKNOWN_ACTION"
            )
        return withContext(Dispatchers.IO) {
            try {
                dispatch(config, action, args)
            } catch (e: InstaboxApiException) {
                FileLogger.e(TAG, "InstaBox API error ${e.code} ${e.errorCode}: ${e.message}")
                ToolResult.Error(e.message ?: "InstaBox error", e.errorCode ?: "INSTABOX_${e.code}")
            } catch (e: Exception) {
                FileLogger.e(TAG, "InstaBox tool failed", e)
                ToolResult.Error(e.message ?: "InstaBox request failed", "INSTABOX_ERROR")
            }
        }
    }

    private suspend fun dispatch(
        config: InstaboxConfig,
        action: String,
        args: Map<String, JsonElement>
    ): ToolResult {
        return when (action) {
            "create" -> createMachine(config, args)
            "connect" -> {
                val id = resolveArgId(args) ?: return missing("machineId")
                val info = client.ensureRunning(config, id)
                settings.setPinnedMachineId(id)
                ok(client.summarizeMachine(info))
            }
            "info" -> ok(client.summarizeMachine(client.ensureRunning(config, resolveMachineId(config, args))))
            "list" -> listMachines(config)
            "kill" -> lifecycle(config, args, "kill")
            "pause" -> lifecycle(config, args, "pause")
            "resume" -> lifecycle(config, args, "resume")
            "stop" -> lifecycle(config, args, "stop")
            "set_auto_suspend" -> setAutoSuspend(config, args)
            "run_code" -> runCode(config, args)
            "run_command" -> runCommand(config, args)
            "list_processes" -> execAndFormat(config, args, "ps -eo pid,user,stat,etime,cmd --no-headers")
            "kill_process" -> {
                val pid = argInt(args, "pid") ?: return missing("pid")
                execAndFormat(config, args, "kill $pid")
            }
            "files_list" -> filesList(config, args)
            "files_read" -> filesRead(config, args)
            "files_write" -> filesWrite(config, args)
            "files_mkdir" -> {
                val path = machinePath(argString(args, "path") ?: HOME)
                execAndFormat(config, args, "mkdir -p ${shellQuote(path)}")
            }
            "files_remove" -> {
                val path = machinePath(argString(args, "path") ?: return missing("path"))
                execAndFormat(config, args, "rm -rf ${shellQuote(path)}")
            }
            "files_exists" -> filesExists(config, args)
            "download" -> filesRead(config, args)
            "get_url" -> publishPort(config, args)
            "unpublish_port" -> {
                val id = resolveMachineId(config, args)
                val port = argInt(args, "port") ?: return missing("port")
                client.ensureRunning(config, id)
                client.requestJson(config, "DELETE", "/v1/machines/$id/ports/$port")
                ok(buildJsonObject { put("ok", true); put("port", port) })
            }
            "list_ports" -> {
                val id = resolveMachineId(config, args)
                client.ensureRunning(config, id)
                ok(client.requestJson(config, "GET", "/v1/machines/$id/ports").jsonObject)
            }
            "install" -> install(config, args)
            "desktop_start" -> desktop(config, args, "start")
            "desktop_status" -> desktop(config, args, "status")
            "desktop_screenshot" -> desktopScreenshot(config, args)
            "desktop_open" -> desktop(config, args, "open")
            "desktop_click" -> desktop(config, args, "click")
            "desktop_type" -> desktop(config, args, "type")
            "desktop_press" -> desktop(config, args, "press")
            "desktop_scroll" -> desktop(config, args, "scroll")
            "desktop_move" -> desktop(config, args, "move")
            "desktop_stream" -> desktopStream(config, args)
            "desktop_revoke" -> {
                val id = resolveMachineId(config, args)
                client.ensureRunning(config, id)
                client.requestJson(config, "DELETE", "/v1/machines/$id/desktop/stream")
                ok(buildJsonObject { put("ok", true); put("revoked", true) })
            }
            "snapshot" -> snapshot(config, args)
            "metrics" -> {
                val id = resolveMachineId(config, args)
                val el = client.requestJson(config, "GET", "/v1/machines/$id/metrics")
                ok(el as? JsonObject ?: buildJsonObject { put("raw", el.toString()) })
            }
            else -> ToolResult.Error("Unknown action: $action", "UNKNOWN_ACTION")
        }
    }

    private suspend fun createMachine(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val template = argString(args, "template") ?: config.template.ifBlank { InstaboxConfig.DEFAULT_TEMPLATE }
        val name = argString(args, "name") ?: "xencode-${UUID.randomUUID().toString().take(8)}"
        val body = buildJsonObject {
            put("name", name)
            put("templateId", template)
            put("vault", false)
            put("metadata", buildJsonObject {
                put("app", "xencode")
                put("role", "remote-sandbox")
            })
            argInt(args, "autoSuspendMs")?.let { ms ->
                put("autoSuspendAfterSeconds", (ms / 1000).coerceAtLeast(0))
            }
        }
        val op = client.requestJson(config, "POST", "/v1/machines", body = body, idempotent = true).jsonObject
        val opId = op["id"]?.jsonPrimitive?.contentOrNull
        if (opId != null) client.waitForOperation(config, opId)
        val machineId = op["machineId"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Create did not return a machine id", "CREATE_FAILED")
        settings.setPinnedMachineId(machineId)
        val info = client.requestJson(config, "GET", "/v1/machines/$machineId").jsonObject
        return ok(client.summarizeMachine(info))
    }

    private suspend fun listMachines(config: InstaboxConfig): ToolResult {
        val el = client.requestJson(config, "GET", "/v1/machines", query = mapOf("limit" to "50"))
        val machines = el.jsonObject["machines"] as? JsonArray ?: JsonArray(emptyList())
        return ok(buildJsonObject {
            put("count", machines.size)
            put("machines", buildJsonArray {
                machines.forEach { item ->
                    (item as? JsonObject)?.let { add(client.summarizeMachine(it)) }
                }
            })
        })
    }

    private suspend fun lifecycle(config: InstaboxConfig, args: Map<String, JsonElement>, kind: String): ToolResult {
        val id = resolveArgId(args) ?: resolveMachineId(config, args)
        val path = when (kind) {
            "kill" -> "/v1/machines/$id"
            "pause" -> "/v1/machines/$id/suspend"
            "resume" -> "/v1/machines/$id/resume"
            "stop" -> "/v1/machines/$id/stop"
            else -> return ToolResult.Error("Unknown lifecycle action", "UNKNOWN_ACTION")
        }
        val method = if (kind == "kill") "DELETE" else "POST"
        val op = client.requestJson(
            config,
            method,
            path,
            idempotent = true,
            confirmDelete = if (kind == "kill") id else null
        ).jsonObject
        op["id"]?.jsonPrimitive?.contentOrNull?.let { client.waitForOperation(config, it) }
        if (kind == "kill" && config.machineId == id) settings.setPinnedMachineId("")
        return ok(buildJsonObject {
            put("ok", true)
            put("action", kind)
            put("machineId", id)
        })
    }

    private suspend fun setAutoSuspend(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        val ms = argInt(args, "autoSuspendMs") ?: return missing("autoSuspendMs")
        val seconds = (ms / 1000).coerceAtLeast(0)
        val info = client.requestJson(
            config,
            "PATCH",
            "/v1/machines/$id",
            body = buildJsonObject { put("autoSuspendAfterSeconds", seconds) },
            idempotent = true
        ).jsonObject
        return ok(client.summarizeMachine(info))
    }

    private suspend fun runCode(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val code = argString(args, "code") ?: return missing("code")
        val language = (argString(args, "language") ?: "python").lowercase()
        val (ext, runner) = when (language) {
            "javascript", "js" -> ".mjs" to "node"
            "typescript", "ts" -> ".ts" to "bash -lc 'command -v bun >/dev/null && bun \"\$FILE\" || npx --yes tsx \"\$FILE\"'"
            "bash", "sh", "shell" -> ".sh" to "bash"
            else -> ".py" to "python3"
        }
        val remote = "/tmp/xencode-run$ext"
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        writeFile(config, id, remote, code)
        val cmd = if (runner.contains("\$FILE")) runner.replace("\$FILE", remote) else "$runner ${shellQuote(remote)}"
        return execAndFormat(config, args, cmd, alreadyEnsured = id)
    }

    private suspend fun runCommand(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val command = argString(args, "command") ?: return missing("command")
        val background = argBool(args, "background")
        val wrapped = if (background) "nohup bash -lc ${shellQuote(command)} >/tmp/xencode-bg.log 2>&1 & echo \$!" else command
        return execAndFormat(config, args, wrapped)
    }

    private suspend fun install(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val packages = argStringList(args, "packages")
        if (packages.isEmpty()) return missing("packages")
        val manager = (argString(args, "manager") ?: "pip").lowercase()
        val cmd = when (manager) {
            "npm" -> "npm install -g ${packages.joinToString(" ") { shellQuote(it) }}"
            else -> "pip install ${packages.joinToString(" ") { shellQuote(it) }}"
        }
        return execAndFormat(config, args, cmd)
    }

    private suspend fun execAndFormat(
        config: InstaboxConfig,
        args: Map<String, JsonElement>,
        command: String,
        alreadyEnsured: String? = null
    ): ToolResult {
        val id = alreadyEnsured ?: resolveMachineId(config, args).also { client.ensureRunning(config, it) }
        val cwd = machinePath(argString(args, "cwd") ?: HOME)
        val timeoutSec = ((argInt(args, "timeoutMs") ?: 120_000).coerceIn(1_000, 600_000) / 1000)
        val result = client.requestJson(
            config,
            "POST",
            "/v1/machines/$id/exec",
            body = buildJsonObject {
                put("cmd", command)
                put("cwd", cwd)
                put("timeoutSeconds", timeoutSec)
            },
            longTimeout = true
        ).jsonObject
        return ok(buildJsonObject {
            put("ok", true)
            put("machineId", id)
            put("exitCode", result["exitCode"] ?: JsonPrimitive(0))
            put("timedOut", result["timedOut"] ?: JsonPrimitive(false))
            put("stdout", JsonPrimitive(clip(result["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(), 6000)))
            put("stderr", JsonPrimitive(clip(result["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(), 4000)))
        })
    }

    private suspend fun filesList(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val path = machinePath(argString(args, "path") ?: HOME)
        val el = client.requestJson(config, "GET", "/v1/machines/$id/files", query = mapOf("path" to path))
        return ok(el as? JsonObject ?: buildJsonObject { put("raw", el.toString()) })
    }

    private suspend fun filesRead(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val path = machinePath(argString(args, "path") ?: return missing("path"))
        val el = client.requestJson(config, "GET", "/v1/machines/$id/files", query = mapOf("path" to path))
        val obj = el as? JsonObject ?: return ok(buildJsonObject { put("raw", clip(el.toString(), 8000)) })
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "dir") return ok(obj)
        val content = obj["content"]?.jsonPrimitive?.contentOrNull
        return ok(buildJsonObject {
            put("ok", true)
            put("path", path)
            put("content", clip(content.orEmpty(), 8000))
            obj["size"]?.let { put("size", it) }
        })
    }

    private suspend fun filesWrite(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val items = mutableListOf<Pair<String, String>>()
        argString(args, "path")?.let { p ->
            val content = argString(args, "content") ?: return missing("content")
            items += machinePath(p) to content
        }
        (args["files"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val p = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val c = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
            items += machinePath(p) to c
        }
        if (items.isEmpty()) return missing("path")
        val written = buildJsonArray {
            items.forEach { (path, content) ->
                writeFile(config, id, path, content)
                add(buildJsonObject { put("path", path); put("ok", true) })
            }
        }
        return ok(buildJsonObject { put("ok", true); put("files", written) })
    }

    private suspend fun filesExists(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val path = machinePath(argString(args, "path") ?: return missing("path"))
        return try {
            client.requestJson(config, "GET", "/v1/machines/$id/files", query = mapOf("path" to path))
            ok(buildJsonObject { put("ok", true); put("exists", true); put("path", path) })
        } catch (e: InstaboxApiException) {
            if (e.code == 404) ok(buildJsonObject { put("ok", true); put("exists", false); put("path", path) })
            else throw e
        }
    }

    private suspend fun writeFile(config: InstaboxConfig, machineId: String, path: String, content: String) {
        val result = client.request(
            config,
            "PUT",
            "/v1/machines/$machineId/files",
            query = mapOf("path" to path, "parents" to "true"),
            rawBody = content.toByteArray(Charsets.UTF_8)
        )
        if (result.code !in 200..299) throw client.apiError(result)
    }

    private suspend fun publishPort(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        val port = argInt(args, "port") ?: return missing("port")
        client.ensureRunning(config, id)
        val body = buildJsonObject {
            put("port", port)
            argString(args, "portName")?.let { put("name", it) }
        }
        return ok(client.requestJson(config, "POST", "/v1/machines/$id/ports", body = body).jsonObject)
    }

    private suspend fun desktop(config: InstaboxConfig, args: Map<String, JsonElement>, kind: String): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val body = when (kind) {
            "start" -> buildJsonObject {
                put("action", "start")
                put("width", argInt(args, "width") ?: 1280)
                put("height", argInt(args, "height") ?: 800)
            }
            "status" -> buildJsonObject { put("action", "status") }
            "open" -> buildJsonObject {
                put("action", "open")
                put("url", argString(args, "url") ?: return missing("url"))
            }
            "click" -> buildJsonObject {
                put("action", "click")
                put("x", argInt(args, "x") ?: return missing("x"))
                put("y", argInt(args, "y") ?: return missing("y"))
                argString(args, "button")?.let { put("button", it) }
                if (argBool(args, "double")) put("double", true)
            }
            "type" -> buildJsonObject {
                put("action", "type")
                put("text", argString(args, "text") ?: return missing("text"))
            }
            "press" -> buildJsonObject {
                put("action", "press")
                put("keys", argString(args, "keys") ?: return missing("keys"))
            }
            "scroll" -> buildJsonObject {
                put("action", "scroll")
                put("direction", argString(args, "direction") ?: "down")
                put("amount", argInt(args, "amount") ?: 3)
            }
            "move" -> buildJsonObject {
                put("action", "move")
                put("x", argInt(args, "x") ?: return missing("x"))
                put("y", argInt(args, "y") ?: return missing("y"))
            }
            else -> return ToolResult.Error("Unknown desktop action", "UNKNOWN_ACTION")
        }
        val el = client.requestJson(config, "POST", "/v1/machines/$id/desktop", body = body)
        return ok(el as? JsonObject ?: buildJsonObject { put("ok", true); put("action", kind) })
    }

    private suspend fun desktopScreenshot(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val result = client.request(
            config,
            "POST",
            "/v1/machines/$id/desktop",
            body = buildJsonObject { put("action", "screenshot") },
            binary = true
        )
        if (result.code !in 200..299) throw client.apiError(result)
        val bytes = result.bytes
        if (bytes != null && bytes.isNotEmpty() && bytes[0] == 0x89.toByte()) {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            return ToolResult.Success(
                buildJsonObject {
                    put("ok", true)
                    put("machineId", id)
                    put("note", "Desktop screenshot attached as image")
                },
                images = listOf(AgentImage(mimeType = "image/png", base64Data = b64))
            )
        }
        val parsed = runCatching { Json.parseToJsonElement(result.body).jsonObject }.getOrNull()
        return ok(parsed ?: buildJsonObject { put("ok", true); put("note", "Screenshot returned without image bytes") })
    }

    private suspend fun desktopStream(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        client.ensureRunning(config, id)
        val body = buildJsonObject {
            put("viewOnly", argBool(args, "viewOnly", default = true))
            put("ttlSeconds", argInt(args, "ttlSeconds") ?: 3600)
        }
        return ok(client.requestJson(config, "POST", "/v1/machines/$id/desktop/stream", body = body).jsonObject)
    }

    private suspend fun snapshot(config: InstaboxConfig, args: Map<String, JsonElement>): ToolResult {
        val id = resolveMachineId(config, args)
        val name = argString(args, "snapshotName") ?: return missing("snapshotName")
        client.ensureRunning(config, id)
        val el = client.requestJson(
            config,
            "POST",
            "/v1/machines/$id/snapshots",
            body = buildJsonObject { put("name", name) },
            idempotent = true
        )
        return ok(el as? JsonObject ?: buildJsonObject { put("ok", true); put("name", name) })
    }

    private suspend fun resolveMachineId(config: InstaboxConfig, args: Map<String, JsonElement>): String {
        resolveArgId(args)?.let { return it }
        if (config.machineId.isNotBlank()) return config.machineId
        val listed = client.requestJson(config, "GET", "/v1/machines", query = mapOf("limit" to "20"))
        val machines = listed.jsonObject["machines"] as? JsonArray ?: JsonArray(emptyList())
        val tagged = machines.mapNotNull { it as? JsonObject }.firstOrNull { obj ->
            obj["metadata"]?.jsonObject?.get("app")?.jsonPrimitive?.contentOrNull == "xencode"
        }
        val first = tagged ?: machines.mapNotNull { it as? JsonObject }.firstOrNull()
        val id = first?.get("id")?.jsonPrimitive?.contentOrNull
        if (id != null) {
            settings.setPinnedMachineId(id)
            return id
        }
        val created = createMachine(config, args)
        if (created is ToolResult.Success) {
            return created.data.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                ?: throw InstaboxApiException(500, "no_machine", "Could not create or find an InstaBox machine")
        }
        throw InstaboxApiException(500, "no_machine", "Could not create or find an InstaBox machine")
    }

    private fun resolveArgId(args: Map<String, JsonElement>): String? =
        argString(args, "machineId") ?: argString(args, "sandboxId")

    companion object {
        private const val TAG = "InstaboxTool"
        const val HOME = "/data/home"
        val ACTIONS = setOf(
            "create", "connect", "info", "list", "kill", "pause", "resume", "stop", "set_auto_suspend",
            "run_code", "run_command", "list_processes", "kill_process",
            "files_list", "files_read", "files_write", "files_mkdir", "files_remove", "files_exists", "download",
            "get_url", "unpublish_port", "list_ports", "install",
            "desktop_start", "desktop_status", "desktop_screenshot", "desktop_open", "desktop_click",
            "desktop_type", "desktop_press", "desktop_scroll", "desktop_move", "desktop_stream", "desktop_revoke",
            "snapshot", "metrics"
        )
        val READ_ONLY_ACTIONS = setOf(
            "info", "list", "files_list", "files_read", "files_exists",
            "list_ports", "desktop_status", "metrics"
        )

        fun cleanAction(value: String?): String? {
            val action = value?.trim()?.lowercase()?.replace('-', '_') ?: return null
            val aliases = mapOf(
                "exec" to "run_code",
                "execute" to "run_code",
                "python" to "run_code",
                "shell" to "run_command",
                "bash" to "run_command",
                "cmd" to "run_command",
                "ls" to "files_list",
                "read" to "files_read",
                "write" to "files_write",
                "mkdir" to "files_mkdir",
                "rm" to "files_remove",
                "destroy" to "kill",
                "delete" to "kill",
                "suspend" to "pause",
                "vnc" to "desktop_stream",
                "screenshot" to "desktop_screenshot"
            )
            val resolved = aliases[action] ?: action
            return resolved.takeIf { it in ACTIONS }
        }

        fun argString(args: Map<String, JsonElement>, key: String): String? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        fun argInt(args: Map<String, JsonElement>, key: String): Int? =
            argString(args, key)?.toIntOrNull()
                ?: (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

        fun argBool(args: Map<String, JsonElement>, key: String, default: Boolean = false): Boolean {
            val raw = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return default
            return raw == "true" || raw == "1"
        }

        fun argStringList(args: Map<String, JsonElement>, key: String): List<String> {
            val el = args[key] ?: return emptyList()
            return when (el) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                is JsonPrimitive -> el.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                else -> emptyList()
            }
        }

        fun machinePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == "~" || trimmed == ".") return HOME
            if (trimmed.startsWith("~/")) return HOME + trimmed.removePrefix("~")
            if (trimmed.startsWith("/")) return trimmed
            return "$HOME/$trimmed"
        }

        fun clip(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max) + "\n…[truncated]"

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun ok(data: JsonObject): ToolResult = ToolResult.Success(data)

        fun missing(param: String): ToolResult =
            ToolResult.Error("Missing required parameter: $param", "MISSING_PARAM")
    }
}
