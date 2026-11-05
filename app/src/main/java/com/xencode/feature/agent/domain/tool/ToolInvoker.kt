package com.xencode.feature.agent.domain.tool

import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.agent.domain.workflow.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** Result of one tool execution: transport text (fed to the model) + error flag + display attachments/images. */
data class ToolRunResult(
    val raw: String,
    val isError: Boolean,
    /** Display-only tools like sendFile: file-card metadata attached to the result, rendered by the UI and never fed back into the model context. */
    val attachments: List<AgentAttachment> = emptyList(),
    val images: List<AgentImage> = emptyList()
)

/**
 * Tool execution component: the workflow's [com.xencode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * delegates each tool execution in a batch here, and this component centrally handles thread dispatching and result post-processing.
 *
 * ## Why IO must be switched here uniformly (a hard-learned fix)
 * The workflow's collector (AIAgentViewModel.executeAgentRequestStream) is a `viewModelScope.launch`
 * without a dispatcher, i.e. `Dispatchers.Main.immediate`. The batch executor's `coroutineScope { async { … } }`
 * inherits that single-threaded dispatcher: without a unified IO switch here,
 * 1. `async` is just a suspension point on one thread, so **parallel batches actually run serially**;
 * 2. tools' blocking IO hogs the main thread directly — Bash's `runCommandSync` (under DEVICE_ROOT it is
 *    `su -c` fork + `Process.waitFor()`), list's per-item `fileAccess.exists/isDirectory`
 *    (one fork per item in ROOT mode), search's `runCommandSyncIfReady`;
 * 3. symptoms: "the whole app freezes when the AI calls a few tools in parallel, a few extra taps trigger an ANR kill (crash)".
 *
 * With a unified IO switch: genuinely parallel on the IO thread pool (64 threads by default), the main thread only dispatches events.
 * Tools needing main-thread cooperation (the browser WebView, the background terminal sessionManager) switch back with their own
 * `withContext(Dispatchers.Main)`, unaffected by this component; tools that already switch to IO internally
 * (readFile etc.) only see a no-op switch.
 *
 * This component also centralizes result post-processing (output capping/trimming, attachment extraction/stripping, exception wrapping),
 * making it independently unit-testable outside the workflow.
 */
class ToolInvoker @Inject constructor(
    private val toolOutputStore: ToolOutputStore
) {
    /** Runs a non-streaming tool. Returns the transport text ready to go into the model context. */
    suspend fun runSync(
        tool: AgentTool?,
        toolCall: ToolCall,
        context: AgentContext
    ): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("Tool $name does not exist", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("Tool execution failed: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * Runs a streaming tool: progress events go back through [onEvent] (accumulated tail capped at [LIVE_TAIL_CHARS] characters,
     * throttled to one per [PROGRESS_INTERVAL_MS]), and the final result is returned at the end.
     * [onEvent] is the workflow's `send` — channelFlow's send can be called from any thread, so IO-thread callback is safe.
     */
    suspend fun runStream(
        tool: StreamingAgentTool,
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        return try {
            withContext(Dispatchers.IO) {
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("Streaming tool returned no result", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("Tool execution failed: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * Extracts file-card metadata from the sendFile tool result's `files` array (including host-local paths, for the UI to open files).
     * If any file lacks a key field, the whole extraction returns empty (consistent with sendFile's atomic semantics).
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** Strips host-local paths from the sendFile result fed back to the model (the model should only see container paths). */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private companion object {
        /** Characters of accumulated output kept for the streaming tool's UI callbacks (so overlong output cannot slow recomposition). */
        const val LIVE_TAIL_CHARS = 4_000
        /** Minimum interval between streaming tool progress events (milliseconds). */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
