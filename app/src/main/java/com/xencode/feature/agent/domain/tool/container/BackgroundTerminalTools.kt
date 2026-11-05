package com.xencode.feature.agent.domain.tool.container

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
import com.xencode.feature.terminal.domain.TerminalSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Extracts the "real content" of a JSON string parameter.
 *
 * Note: do not use `toString().removeSurrounding("\"")`: [toString] returns the JSON-serialized form (outer quotes present,
 * inner quotes escaped as `\"`), so after stripping the outer quotes the inner `\"` remains literal, turning `echo "*.vue"` into
 * `echo \"*.vue\"` and causing the shell to print the quotes literally. Use [jsonPrimitive]/[contentOrNull] to get the decoded content.
 */
private fun JsonElement.asPlainString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * Unified background terminal session tool: a single `action` parameter distinguishes the operations, merging the former
 * `run_background_command` / `send_terminal_input` / `read_terminal_output` tools into one.
 *
 * - `start`: opens a long-lived command (e.g. `npm run dev`, service processes) in a persistent background terminal tab and returns tab_id immediately,
 *   without blocking to wait. The command keeps running; the AI later continues with send/read using the returned id.
 * - `send`: sends a line of command/input to a background or interactive terminal by tab_id (auto-presses Enter by default), then briefly captures output.
 * - `key`: sends common shortcut keys/control characters (e.g. Ctrl-C, Ctrl-D, arrow keys) by tab_id.
 * - `read`: reads all current output of that terminal by tab_id (including live background command logs); omitting tab_id lists all tabs.
 * - `close`: proactively closes and destroys a terminal tab by tab_id.
 *
 * Authorization: the start action matches by shell command prefix (see [com.xencode.feature.agent.domain.permission.ToolPermissionPolicyEngine]),
 * the send action matches by input content, and key/read/close match the tool as a whole.
 */
class TerminalSessionTool @Inject constructor(
    private val sessionManager: TerminalSessionProvider
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "TerminalSessionTool"

        /** Maximum time (ms) to capture initial output after start: returns immediately if the command exits early, otherwise waits the full duration. */
        const val START_CAPTURE_MS = 5_000L

        /** Interval (ms) for polling whether the command has exited. */
        const val START_POLL_INTERVAL_MS = 200L

        val SUPPORTED_KEYS = listOf(
            "ctrl+c",
            "ctrl+d",
            "ctrl+z",
            "ctrl+l",
            "ctrl+u",
            "ctrl+w",
            "esc",
            "tab",
            "enter",
            "up",
            "down",
            "left",
            "right"
        )
    }

    override val name = "terminal"
    override val description =
        "Manage persistent background terminal sessions (start a command, send input/keys, read output, close tabs). For tasks that exit on their own and need a result, use start with notify=true (the system notifies when done; do not poll). For long-running services use notify=false and read when needed. Interrupt a foreground process with key=ctrl+c."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (actionOf(args)) {
            "read" -> setOf(ToolCapability.READ_WORKSPACE)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action: start=run a background command; send=send input to a terminal; key=send a shortcut; read=read terminal output; close=close a terminal tab",
            required = true,
            enum = listOf("start", "send", "key", "read", "close")
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Required for start: the shell command to run in the background, e.g. 'npm run dev'",
            required = false
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "Optional for start: tab display name; defaults to the tab id",
            required = false
        ),
        "notify" to ToolParameter(
            name = "notify",
            type = ParameterType.BOOLEAN,
            description = "Optional for start: whether the system notifies the AI when the command exits. true for tasks that finish on their own (compile/test); start captures ~5s of initial output, then notifies — do not poll. false (default) for long-running services like a dev server; the tab stays alive for reuse.",
            required = false
        ),
        "tab_id" to ToolParameter(
            name = "tab_id",
            type = ParameterType.STRING,
            description = "For send/key/read/close: target terminal tab id, e.g. 'term-2'. Omit on read to list all tabs",
            required = false
        ),
        "input" to ToolParameter(
            name = "input",
            type = ParameterType.STRING,
            description = "Required for send: command or text to send",
            required = false
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "Required for key: shortcut or control character. Common: ctrl+c interrupt, ctrl+d EOF, ctrl+z suspend, esc/tab/enter/arrows for interactive programs",
            required = false,
            enum = SUPPORTED_KEYS
        ),
        "submit" to ToolParameter(
            name = "submit",
            type = ParameterType.BOOLEAN,
            description = "Optional for send: append Enter to run the command; default true",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "Elevate retry: if start/send was blocked by built-in safety (e.g. catastrophic deletes) and you still need it, set true to retry. The user is prompted for a one-time grant (not remembered). No effect in PLAN mode.",
            required = false
        )
    )

    private fun actionOf(args: Map<String, JsonElement>): String =
        args["action"]?.asPlainString()?.trim()?.lowercase() ?: ""

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission = when (actionOf(args)) {
        "start" -> {
            val command = args["command"]?.asPlainString() ?: "unknown command"
            val title = args["title"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm background command",
                summary = command,
                details = "Tab: ${title?.takeIf { it.isNotBlank() } ?: "auto-named"}\nRuns until the process exits or is stopped.",
                argsPreview = argsPreview
            )
        }
        "send" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "unknown tab"
            val input = args["input"]?.asPlainString() ?: "unknown input"
            val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm terminal input",
                summary = input,
                details = "Target tab: $tabId\nSubmit with Enter: ${if (submit) "yes" else "no"}",
                argsPreview = argsPreview
            )
        }
        "key" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "unknown tab"
            val key = args["key"]?.asPlainString() ?: "unknown key"
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm terminal key",
                summary = key,
                details = "Target tab: $tabId",
                argsPreview = argsPreview
            )
        }
        "close" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "unknown tab"
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm close terminal tab",
                summary = tabId,
                details = "Will close tab $tabId and stop any process still running in it.",
                argsPreview = argsPreview
            )
        }
        else -> {
            val tabId = args["tab_id"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm read terminal output",
                summary = tabId?.takeIf { it.isNotBlank() } ?: "List all tabs",
                details = if (tabId.isNullOrBlank()) "List all current terminal tabs and their status" else "Read current output of tab $tabId",
                argsPreview = argsPreview
            )
        }
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = execute(args, null)

    /** The non-streaming fallback path also carries the originating session id, matching [executeStream] (so background command notifications return to the source session). */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: com.xencode.feature.agent.domain.model.AgentContext
    ): ToolResult = execute(args, context.sessionId)

    private suspend fun execute(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult =
        when (actionOf(args)) {
            "start" -> start(args, sourceSessionId)
            "send" -> send(args)
            "key" -> sendKey(args)
            "read" -> read(args)
            "close" -> close(args)
            else -> ToolResult.Error("Missing or invalid action (must be start/send/key/read/close)")
        }

    /**
     * On the event-stream path, start/send push new output to the UI in real time during the wait window, like Bash.
     * Other actions keep non-streaming semantics and return the final result directly.
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.xencode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        when (actionOf(args)) {
            "start" -> streamStart(args, context)
            "send" -> streamSend(args)
            else -> emit(ToolStreamEvent.Completed(execute(args)))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamStart(
        args: Map<String, JsonElement>,
        context: com.xencode.feature.agent.domain.model.AgentContext
    ) {
        val command = args["command"]?.asPlainString()
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("start is missing required parameter: command")))
            return
        }
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        val tabId = try {
            withContext(Dispatchers.Main) { sessionManager.startBackgroundCommand(command, title, notify, context.sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start background command: $command", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("Failed to start background command: ${e.message}")))
            return
        }

        FileLogger.i(TAG, "Background command started tab=$tabId (streaming capture): $command")
        captureOutputFor(tabId, initialOutput = "")
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "start") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamSend(args: Map<String, JsonElement>) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("send is missing required parameter: tab_id")))
            return
        }
        val input = args["input"]?.asPlainString()
        if (input == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("send is missing required parameter: input")))
            return
        }
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true

        val before = withContext(Dispatchers.Main) { sessionManager.getTabOutput(tabId) }
        if (before == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Terminal tab not found: $tabId")))
            return
        }

        val ok = withContext(Dispatchers.Main) { sessionManager.sendInput(tabId, input, appendNewline = submit) }
        if (!ok) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Terminal tab not found: $tabId")))
            return
        }
        FileLogger.i(TAG, "Sent input to $tabId (streaming capture): $input")

        captureOutputFor(tabId, initialOutput = before)
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "send") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.captureOutputFor(
        tabId: String,
        initialOutput: String
    ) {
        var elapsed = 0L
        var lastOutput = initialOutput
        while (elapsed < START_CAPTURE_MS) {
            delay(START_POLL_INTERVAL_MS)
            elapsed += START_POLL_INTERVAL_MS

            val output = withContext(Dispatchers.Main) { sessionManager.getTabOutput(tabId) }
                ?: break
            val delta = outputDelta(lastOutput, output)
            if (delta.isNotEmpty()) {
                emit(ToolStreamEvent.Progress(delta))
                lastOutput = output
            }
        }
    }

    /**
     * Starts a background command tab and returns after capturing initial output for up to [START_CAPTURE_MS] ms.
     *
     * Unlike a plain start that ends by returning just the tab_id — which would force the AI to call read again to learn whether
     * the command failed immediately (e.g. command not found, port in use) — this polls after startup: returns immediately if the
     * command exits early (fail fast, visible early), otherwise waits the full [START_CAPTURE_MS] to grab some initial output
     * (dev server startup banners, first errors, etc. usually appear within these seconds).
     * The returned object contains tab_id / whether still running / captured output, letting the AI decide whether to continue with send/read.
     *
     * TerminalSessionManager requires the main thread internally, so the whole block switches to Main.
     */
    private suspend fun start(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult = withContext(Dispatchers.Main) {
        val command = args["command"]?.asPlainString()
            ?: return@withContext ToolResult.Error("start is missing required parameter: command")
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        try {
            val tabId = sessionManager.startBackgroundCommand(command, title, notify, sourceSessionId)
            FileLogger.i(TAG, "Background command started tab=$tabId: $command")

            // Poll to capture initial output: end early if the command exits, otherwise wait up to START_CAPTURE_MS.
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
                val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
                if (!running) break
            }

            val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
            val output = sessionManager.getTabOutput(tabId) ?: ""
            FileLogger.v(TAG, "start captured tab=$tabId running=$running output ${output.length} chars")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "tab_id" to JsonPrimitive(tabId),
                        "running" to JsonPrimitive(running),
                        "output" to JsonPrimitive(output)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start background command: $command", e)
            ToolResult.Error("Failed to start background command: ${e.message}")
        }
    }

    /**
     * Sends a line of input to the given terminal (Enter by default) and waits briefly to capture output.
     *
     * Consistent with start: returns tab_id / running / output so the AI need not manually read again after sending
     * a command to see the immediate result. An interactive shell has no reliable "command finished" signal, so this waits a fixed [START_CAPTURE_MS].
     */
    private suspend fun send(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("send is missing required parameter: tab_id")
        val input = args["input"]?.asPlainString()
            ?: return@withContext ToolResult.Error("send is missing required parameter: input")
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
        val ok = sessionManager.sendInput(tabId, input, appendNewline = submit)
        if (ok) {
            FileLogger.i(TAG, "Sent input to $tabId: $input")
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
            }
            capturedResult(tabId, actionLabel = "send")
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("Terminal $tabId has ended; cannot send commands. Use start to open a new terminal", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("Terminal tab not found: $tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** Sends a predefined shortcut key/control character to the given terminal. TerminalSessionManager requires the main thread. */
    private suspend fun sendKey(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("key is missing required parameter: tab_id")
        val key = args["key"]?.asPlainString()
            ?: return@withContext ToolResult.Error("key is missing required parameter: key")
        val normalized = normalizeKey(key)
        val ok = when (normalized) {
            "ctrl+c" -> sessionManager.writeBytesToTab(tabId, 0x03)
            "ctrl+d" -> sessionManager.writeBytesToTab(tabId, 0x04)
            "ctrl+z" -> sessionManager.writeBytesToTab(tabId, 0x1A)
            "ctrl+l" -> sessionManager.writeBytesToTab(tabId, 0x0C)
            "ctrl+u" -> sessionManager.writeBytesToTab(tabId, 0x15)
            "ctrl+w" -> sessionManager.writeBytesToTab(tabId, 0x17)
            "esc", "escape" -> sessionManager.writeBytesToTab(tabId, 0x1B)
            "tab" -> sessionManager.writeBytesToTab(tabId, 0x09)
            "enter", "return" -> sessionManager.writeBytesToTab(tabId, 0x0D)
            "up" -> sessionManager.writeToTab(tabId, "\u001B[A")
            "down" -> sessionManager.writeToTab(tabId, "\u001B[B")
            "right" -> sessionManager.writeToTab(tabId, "\u001B[C")
            "left" -> sessionManager.writeToTab(tabId, "\u001B[D")
            else -> return@withContext ToolResult.Error("Unsupported key: $key; supported: ${SUPPORTED_KEYS.joinToString(", ")}")
        }
        if (ok) {
            FileLogger.i(TAG, "Sent key to $tabId: $normalized")
            ToolResult.Success(JsonPrimitive("Sent key $normalized to terminal tab $tabId. Use terminal(action=\"read\", tab_id=\"$tabId\") to see the result."))
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("Terminal $tabId has ended; cannot send keys", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("Terminal tab not found: $tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** Reads terminal output; lists all tabs if tab_id is omitted. TerminalSessionManager requires the main thread. */
    private suspend fun read(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            val list = sessionManager.listTabs()
            if (list.isEmpty()) return@withContext ToolResult.Success(JsonPrimitive("No terminal tabs."))
            val text = list.joinToString("\n") {
                val state = if (it.running) "running" else "ended"
                val kind = if (it.isBackground) "background" else "interactive"
                "${it.id}  [$kind/$state]  ${it.title}" + (it.command?.let { c -> "  ($c)" } ?: "")
            }
            return@withContext ToolResult.Success(JsonPrimitive(text))
        }
        val output = sessionManager.getTabOutput(tabId)
            ?: return@withContext ToolResult.Error("Terminal tab not found: $tabId")
        FileLogger.v(TAG, "Read $tabId output ${output.length} chars")
        ToolResult.Success(JsonPrimitive(output))
    }

    /** Closes the given terminal tab. TerminalSessionManager requires the main thread. */
    private suspend fun close(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("close is missing required parameter: tab_id")
        val ok = sessionManager.closeTab(tabId)
        if (ok) {
            FileLogger.i(TAG, "Closed terminal tab: $tabId")
            ToolResult.Success(JsonPrimitive("Closed terminal tab $tabId."))
        } else {
            ToolResult.Error("Terminal tab not found: $tabId")
        }
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace(" ", "").replace("_", "+").replace("-", "+")

    private fun capturedResult(tabId: String, actionLabel: String): ToolResult {
        val tab = sessionManager.listTabs().firstOrNull { it.id == tabId }
            ?: return ToolResult.Error("Terminal tab not found: $tabId")
        val rawOutput = sessionManager.getTabOutput(tabId) ?: ""
        FileLogger.v(
            TAG,
            "$actionLabel captured tab=$tabId running=${tab.running} output ${rawOutput.length} chars"
        )
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "tab_id" to JsonPrimitive(tabId),
                    "running" to JsonPrimitive(tab.running),
                    "output" to JsonPrimitive(rawOutput)
                )
            )
        )
    }

    private fun outputDelta(previous: String, current: String): String {
        if (current == previous) return ""
        if (current.startsWith(previous)) return current.substring(previous.length)

        val maxOverlap = minOf(previous.length, current.length)
        for (length in maxOverlap downTo 1) {
            if (previous.endsWith(current.take(length))) {
                return current.substring(length)
            }
        }
        return current
    }
}
