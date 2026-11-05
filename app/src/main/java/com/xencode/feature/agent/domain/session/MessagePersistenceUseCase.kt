package com.xencode.feature.agent.domain.session

import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.data.local.database.AgentDatabase
import com.xencode.feature.agent.data.local.entity.AgentMessageEntity
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.xencode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.agent.presentation.MessageRole
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePersistenceUseCase @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Change version for the agent_messages table: any write path (including rewind deletes, compaction markers, cold-start cleanup) increments it,
     *  serving as the invalidation signal for the [buildHistory] cache. InvalidationTracker watches table-level changes, covering all DAO writes. */
    private val dbVersion = java.util.concurrent.atomic.AtomicLong(0)
    private val historyCache = HashMap<String, HistoryEntry>()

    private class HistoryEntry(
        val version: Long,
        val pendingToolMarker: String,
        val messages: List<AgentMessage>
    )

    init {
        agentDatabase.invalidationTracker.addObserver(
            object : androidx.room.InvalidationTracker.Observer(arrayOf("agent_messages")) {
                override fun onInvalidated(tables: Set<String>) {
                    dbVersion.incrementAndGet()
                }
            }
        )
    }

    /** LRU cache of embedded-image base64: key = path:size:lastModified, so each LLM call in the tool loop
     *  does not re-read and re-encode the file. Capped by both entry count and total bytes. */
    private val imageBase64Cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_IMAGE_CACHE_ENTRIES
    }
    private var imageCacheBytes = 0L

    private fun cachedImageBase64(key: String): String? = synchronized(imageBase64Cache) { imageBase64Cache[key] }

    private fun cacheImageBase64(key: String, value: String) {
        synchronized(imageBase64Cache) {
            val old = imageBase64Cache.put(key, value)
            imageCacheBytes += value.length - (old?.length ?: 0)
            while (imageCacheBytes > MAX_IMAGE_CACHE_BYTES && imageBase64Cache.isNotEmpty()) {
                val it = imageBase64Cache.entries.iterator()
                val eldest = it.next()
                it.remove()
                imageCacheBytes -= eldest.value.length
            }
        }
    }

    // Monotonic timestamp: keeps the order of same-millisecond writes stable (assistant always before its tool result).
    @Volatile
    private var lastTimestamp = 0L

    @Synchronized
    fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    suspend fun persist(
        sessionId: String,
        role: MessageRole,
        content: String,
        id: String = UUID.randomUUID().toString(),
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        isError: Boolean = false,
        reasoning: String? = null,
        signature: String? = null,
        thinkingBlocksJson: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cachedInputTokens: Int = 0,
        isCompacted: Boolean = false,
        isContextExcluded: Boolean = false
    ) {
        agentMessageDao.insert(
            AgentMessageEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                content = sanitizeContent(content),
                timestamp = nextTimestamp(),
                toolCallsJson = if (toolCalls.isNotEmpty()) capBytes(json.encodeToString(toolCalls), MAX_SNAPSHOT_BYTES) else null,
                toolCallId = toolCallId,
                toolName = toolName,
                toolArgs = toolArgs?.let { capBytes(it, MAX_TOOL_ARGS_BYTES) },
                isError = isError,
                reasoning = reasoning?.let { sanitizeContent(it) },
                signature = signature?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
                thinkingBlocksJson = thinkingBlocksJson?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
                attachmentsJson = if (attachments.isNotEmpty()) capBytes(json.encodeToString(attachments), MAX_ATTACHMENTS_BYTES) else null,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens,
                isCompacted = isCompacted,
                isContextExcluded = isContextExcluded
            )
        )
    }

    fun invalidateHistory(sessionId: String) {
        dbVersion.incrementAndGet()
        synchronized(historyCache) {
            historyCache.remove(sessionId)
        }
    }

    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        agentMessageDao.rewindConversation(sessionId, cutoff)
        invalidateHistory(sessionId)
    }

    suspend fun updateContent(messageId: String, newContent: String) {
        agentMessageDao.updateMessageContent(messageId, sanitizeContent(newContent))
    }

    companion object {
        /**
         * Persistence cap for each text field of a message (UTF-8 bytes). Far below SQLite CursorWindow's
         * ~2MB single-window hard limit, preventing oversized rows from crashing message reads.
         *
         * Byte-based, not char-based: CJK text can use up to 3 bytes per char, so a char count does not bound the real footprint.
         * A single field cannot be capped alone either — one message can carry several large snapshots at once (body, reasoning, tool arguments),
         * so the sum of field caps (~720KB) must stay under the window size as a whole, or the row may have nowhere
         * to fit due to window prefilling, throwing IllegalStateException "Couldn't read row N, col 0 from CursorWindow" on read.
         */
        const val MAX_CONTENT_BYTES = 150_000
        const val MAX_SNAPSHOT_BYTES = 100_000
        const val MAX_ATTACHMENTS_BYTES = 20_000
        /** Tool arguments cap: same order as the body cap, so long arguments (e.g. large file contents) persist fully and the UI limits height with scrolling. */
        const val MAX_TOOL_ARGS_BYTES = 150_000
        const val IMAGE_OMITTED_MARKER = "[Image omitted: inline image data too large]"
        const val CONTENT_TRUNCATED_MARKER = "…[Content too long; truncated]"
        /** Max entries in the image base64 cache. */
        private const val MAX_IMAGE_CACHE_ENTRIES = 12
        /** Total byte cap for the image base64 cache (base64 is ~4/3 of the original size; 48MB holds about 36MB of original images). */
        private const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

        /** Inline base64 image data URL (`data:image/...;base64,...`). */
        private val INLINE_BASE64_IMAGE = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\r\n]+""")

        /** A UTF-8 char takes at most 3 bytes, so if three times [String.length] fits the cap, no real encoding check is needed. */
        private fun definitelyFits(raw: String, maxBytes: Int): Boolean =
            raw.length.toLong() * 3 <= maxBytes

        /**
         * Content sanitization before persisting, a uniform safety net for all providers/models:
         * 1. Strip inline base64 image data URLs (replaced with a placeholder note); such content should never land in database text;
         * 2. Anything still oversized after stripping is truncated to [MAX_CONTENT_BYTES] by UTF-8 bytes, so no oversized row ever triggers a CursorWindow crash.
         */
        internal fun sanitizeContent(raw: String): String {
            if (definitelyFits(raw, MAX_CONTENT_BYTES) && !raw.contains("data:image/", ignoreCase = true)) {
                return raw
            }
            val stripped = INLINE_BASE64_IMAGE.replace(raw, IMAGE_OMITTED_MARKER)
            val capped = capBytes(stripped, MAX_CONTENT_BYTES)
            return if (capped.length < stripped.length) capped + CONTENT_TRUNCATED_MARKER else capped
        }

        /**
         * Byte-truncates (UTF-8) non-display text before persisting: JSON snapshot fields (toolCallsJson / thinkingBlocksJson / attachmentsJson) and
         * reasoning signatures. Truncated JSON is no longer parseable; readers use runCatching to
         * degrade to "no tool calls / no reasoning snapshot / no attachments" instead of crashing. No truncation marker is appended, to spare parsers meaningless extra content.
         * Truncation happens on code-point boundaries, never splitting a surrogate pair.
         */
        internal fun capBytes(raw: String, maxBytes: Int): String {
            if (definitelyFits(raw, maxBytes)) return raw
            var used = 0
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val isPair = c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                val charBytes = when {
                    isPair -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                if (used + charBytes > maxBytes) break
                used += charBytes
                i += if (isPair) 2 else 1
            }
            return if (i >= raw.length) raw else raw.substring(0, i)
        }
    }

    /**
     * Rebuilds a valid context history from persisted messages.
     * Key point: keep only parts where an "assistant tool_call" and its "tool result" pair up,
     * dropping dangling items missing either side, so replay never produces orphan tool_use / tool_result that violate API constraints.
     * Messages already marked as compacted (isCompacted=true) do not take part in replay.
     */
    suspend fun buildHistory(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        // Versioned cache: when the agent_messages table is unchanged and the marker matches, reuse the last rebuild,
        // avoiding a full DB read + multiple passes + JSON decoding on every LLM call in the tool loop.
        // InvalidationTracker covers all write paths (including rewind deletes, compaction markers, cold-start cleanup), so no invalidation is missed.
        val version = dbVersion.get()
        synchronized(historyCache) {
            historyCache[sessionId]?.let { cached ->
                if (cached.version == version && cached.pendingToolMarker == pendingToolMarker) {
                    return cached.messages
                }
            }
        }
        val messages = buildHistoryUncached(sessionId, pendingToolMarker)
        synchronized(historyCache) {
            historyCache[sessionId] = HistoryEntry(version, pendingToolMarker, messages)
        }
        return messages
    }

    private suspend fun buildHistoryUncached(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        val entities = agentMessageDao.getMessagesBySessionOnce(sessionId)
            .filter { !it.isCompacted && !it.isContextExcluded }

        // First pass: intersect assistant-declared toolCallIds with tool-result toolCallIds.
        val declaredIds = mutableSetOf<String>()
        val resultIds = mutableSetOf<String>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.ASSISTANT -> e.toolCallsJson?.let {
                    runCatching { json.decodeFromString<List<ToolCall>>(it) }
                        .getOrNull()?.forEach { tc -> declaredIds.add(tc.id) }
                }
                MessageRole.TOOL -> {
                    // Only genuinely finished results count toward pairing; in-progress placeholder rows (orphans whose completion event never came back) do not.
                    if (!e.content.startsWith(pendingToolMarker) &&
                        !e.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
                    ) {
                        e.toolCallId?.let { resultIds.add(it) }
                    }
                }
                else -> {}
            }
        }
        val validIds = declaredIds intersect resultIds

        // Second pass: build messages, filtering out tool calls / tool results that cannot be paired.
        val result = mutableListOf<AgentMessage>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val rawContent = if (e.isCompactionMarker) CONTEXT_COMPACTION_MARKER else e.content
                    val attachments = if (!e.isCompactionMarker) {
                        e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                        } ?: emptyList()
                    } else emptyList()

                    val finalContent = if (attachments.isNotEmpty()) {
                        val attachmentText = buildString {
                            append("Attachments: ")
                            attachments.forEach { att ->
                                append('\n')
                                append("- ")
                                append(att.fileName)
                                append(": ")
                                append(att.containerPath)
                            }
                        }
                        if (rawContent.isBlank()) attachmentText else "${rawContent.trimEnd()}\n\n$attachmentText"
                    } else {
                        rawContent
                    }

                    val images = attachments.mapNotNull { it.toAgentImage() }

                    result.add(
                        AgentMessage.UserMessage(
                            id = e.id,
                            content = finalContent,
                            images = images
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = e.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.filter { it.id in validIds } ?: emptyList()
                    val imageAttachments = e.attachmentsJson?.let {
                        runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                    }.orEmpty()
                    if (e.content.isNotBlank() || toolCalls.isNotEmpty() || imageAttachments.isNotEmpty()) {
                        val previous = result.lastOrNull()
                        if (
                            e.isContextSummary &&
                            !(previous is AgentMessage.UserMessage && previous.content == CONTEXT_COMPACTION_MARKER)
                        ) {
                            result.add(AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER))
                        }
                        result.add(
                            AgentMessage.AssistantMessage(
                                id = e.id,
                                content = e.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart(),
                                toolCalls = toolCalls,
                                reasoning = e.reasoning ?: "",
                                signature = e.signature ?: "",
                                thinkingBlocksJson = e.thinkingBlocksJson ?: "",
                                inputTokens = e.inputTokens,
                                // Rebuild base64 for attachment images by path (cached), for the next round of context replay.
                                images = imageAttachments.mapNotNull { it.toAgentImage() }
                            )
                        )
                    }
                }
                MessageRole.TOOL -> {
                    val tcId = e.toolCallId
                    if (tcId != null && tcId in validIds) {
                        result.add(
                            AgentMessage.ToolResultMessage(
                                id = tcId,
                                messageId = e.id,
                                toolName = e.toolName ?: "unknown",
                                result = e.content
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun AgentAttachment.toAgentImage(): com.xencode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        // Cache base64 by path+size+mtime: reuse directly when the file is unchanged, skipping the re-read+encode on every LLM call.
        val key = "$localPath:${file.length()}:${file.lastModified()}"
        cachedImageBase64(key)?.let { cached ->
            return com.xencode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = cached,
                path = containerPath
            )
        }
        return try {
            val bytes = file.readBytes()
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            cacheImageBase64(key, base64)
            com.xencode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = base64,
                path = containerPath
            )
        } catch (e: Exception) {
            null
        }
    }
}
