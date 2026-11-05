package com.xencode.feature.agent.domain.tool.file

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLConnection
import javax.inject.Inject

/**
 * Sends workspace files to the chat area as "file rows" (one row per file: thumbnail/type icon + name + size·path);
 * tapping opens them with the matching system app, while images get a full-screen in-app preview.
 *
 * Atomic semantics: every file must exist and be valid; any single failure fails the whole call (no file rows are rendered),
 * the error lists each failed item, and the AI can fix paths and call again.
 * File data (content/base64) never enters the model context; the model only receives file metadata text.
 */
class SendFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val generalSettingsRepository: GeneralSettingsRepository
) : AgentTool() {
    override val name = "sendFile"

    /**
     * The per-file size cap is adjustable in preferences (default 100MB). The tool definition ([description] / [parameters]) is read
     * by the model side and is a non-suspending property, so [cachedMaxFileSizeMb] caches the latest setting; actual validation
     * uses the authoritative value read suspendingly inside [execute].
     */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedMaxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB

    init {
        settingsScope.launch {
            generalSettingsRepository.sendFileMaxSizeMbFlow.collect { cachedMaxFileSizeMb = it }
        }
    }

    override val description get() =
        "Send existing workspace files to the chat for the user. All files must exist and be valid; any failure fails the whole call. Display only — does not read content into context."

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters get() = mapOf(
        "paths" to ToolParameter(
            name = "paths",
            type = ParameterType.ARRAY,
            description = "Paths to send. Supports ~/workspace/... project files or absolute container paths. At most $MAX_FILES files; each file ≤ ${cachedMaxFileSizeMb}MB.",
            required = true,
            itemsSchema = mapOf("type" to "string")
        ),
        "names" to ToolParameter(
            name = "names",
            type = ParameterType.ARRAY,
            description = "Optional display names, one-to-one with paths; defaults to the file name.",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val paths = (args["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (paths.isEmpty()) {
                return ToolResult.Error("Missing required parameter: paths", "MISSING_PATHS")
            }
            if (paths.size > MAX_FILES) {
                return ToolResult.Error("At most $MAX_FILES files per call, got ${paths.size}", "TOO_MANY_FILES")
            }
            val names = (args["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                .orEmpty()
            if (names.isNotEmpty() && names.size != paths.size) {
                return ToolResult.Error("names count (${names.size}) does not match paths count (${paths.size})", "ARGS_MISMATCH")
            }

            // Atomic validation: success only if all pass; any failure fails everything and lists all failed items.
            val maxSizeMb = generalSettingsRepository.sendFileMaxSizeMb()
            val maxSizeBytes = maxSizeMb * 1024L * 1024L
            val failures = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                when {
                    !fileAccess.exists(path) -> failures.add("File not found: $path")
                    !fileAccess.isFile(path) -> failures.add("Path is not a file: $path")
                    fileAccess.fileSize(path) > maxSizeBytes -> {
                        val sizeMb = fileAccess.fileSize(path) / (1024 * 1024)
                        failures.add("File exceeds ${maxSizeMb}MB limit: $path (${sizeMb}MB)")
                    }
                }
            }
            if (failures.isNotEmpty()) {
                FileLogger.w(TAG, "sendFile atomic validation failed: ${failures.joinToString("; ")}")
                return ToolResult.Error(failures.joinToString("; "), "INVALID_FILE")
            }

            val files = paths.mapIndexed { index, path ->
                val rawName = path.substringAfterLast('/').ifBlank { path }
                // Display names are customizable; the MIME type must be inferred from the original file name, so a custom name missing a suffix or carrying a prefix does not affect type detection.
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: rawName
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                        "local_path" to JsonPrimitive(fileAccess.copyToLocal(path).absolutePath),
                        "name" to JsonPrimitive(displayName),
                        "mime_type" to JsonPrimitive(guessMimeType(rawName)),
                        "size_bytes" to JsonPrimitive(fileAccess.fileSize(path)),
                        "is_image" to JsonPrimitive(guessMimeType(rawName).startsWith("image/"))
                    )
                )
            }

            FileLogger.i(TAG, "sendFile ok: ${paths.size} files ${paths.joinToString(", ") { fileAccess.toDisplayPath(it) }}")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(
                            buildString {
                                append("Sent ${files.size} file(s):")
                                files.forEach { file ->
                                    append('\n')
                                    append("- ")
                                    append(file["name"]?.jsonPrimitive?.contentOrNull)
                                    append(": ")
                                    append(file["path"]?.jsonPrimitive?.contentOrNull)
                                }
                            }
                        ),
                        "files" to JsonArray(files),
                        "file_count" to JsonPrimitive(files.size)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendFile exception", e)
            ToolResult.Error(e.message ?: "Failed to send file", "SEND_FILE_ERROR")
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extMime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private companion object {
        const val TAG = "SendFileTool"
        const val MAX_FILES = 10
        /** Fallback value before settings are ready; matches the default in preferences. */
        const val DEFAULT_MAX_FILE_SIZE_MB = 100
    }
}
