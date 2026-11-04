package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import java.io.File

/** One edit item; semantics match editFile edits. */
data class MemoryEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean
)

/** Result of a memory edit. */
sealed interface MemoryEditResult {
    /** Edit succeeded and was written. */
    data object Success : MemoryEditResult

    /** No memory with that name. */
    data class NotFound(val name: String) : MemoryEditResult

    /** Validation or write failed. */
    data class Error(val code: String, val message: String) : MemoryEditResult
}

/**
 * Memory data source: global or project-scoped.
 */
interface MemorySource {
    /** Scan and return all valid memories in this source. */
    fun listMemories(): List<Memory>

    /** Load the full body of a named memory; null if missing or unreadable. */
    fun loadContent(name: String): String?

    /** Save a memory (create or overwrite). */
    fun saveMemory(name: String, description: String, content: String): Boolean

    /**
     * Locally edit an existing memory body (exact old_string/new_string match), same semantics as editFile.
     * Default impl locates the file via [listMemories] and applies edits in memory;
     * any validation failure skips the write (atomic). On success, reformats with the original name/description.
     */
    fun editMemory(name: String, edits: List<MemoryEdit>): MemoryEditResult {
        val memory = listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MemoryEditResult.NotFound(name)
        val file = memory.file ?: return MemoryEditResult.NotFound(name)
        if (edits.isEmpty()) {
            return MemoryEditResult.Error("EMPTY_EDITS", "edits cannot be empty; provide at least one {old_string,new_string} edit")
        }

        var content = memory.content
        edits.forEachIndexed { i, e ->
            if (e.oldString.isEmpty()) {
                return MemoryEditResult.Error("EMPTY_OLD_STRING", "Edit ${i + 1}: old_string cannot be empty")
            }
            if (e.oldString == e.newString) {
                return MemoryEditResult.Error("NO_OP", "Edit ${i + 1}: old_string and new_string are identical; nothing to change")
            }
            val occurrences = content.split(e.oldString).size - 1
            if (occurrences == 0) {
                return MemoryEditResult.Error(
                    "NO_MATCH",
                    "Edit ${i + 1}: old_string was not found in memory \"${memory.name}\". Confirm the text matches the body returned by read exactly (including indentation/newlines)"
                )
            }
            if (occurrences > 1 && !e.replaceAll) {
                return MemoryEditResult.Error(
                    "MULTIPLE_MATCHES",
                    "Edit ${i + 1}: old_string matched $occurrences places. Provide a longer unique context, or set replace_all=true for this edit"
                )
            }
            content = if (e.replaceAll) content.replace(e.oldString, e.newString)
            else content.replaceFirst(e.oldString, e.newString)
        }

        return try {
            file.writeText(MemoryParser.format(memory.name, memory.description, content))
            MemoryEditResult.Success
        } catch (e: Exception) {
            FileLogger.e("MemorySource", "Failed to edit memory: $name", e)
            MemoryEditResult.Error("EDIT_WRITE_FAILED", "Failed to write memory file: ${e.message}")
        }
    }

    fun deleteMemory(name: String): Boolean

    companion object {
        /**
         * 将模型传入的记忆名归一化为安全文件名片段。
         *
         * 记忆名来自大模型工具参数（自由文本），不经处理直接拼到 "$name.md" 会引入路径穿越
         * （如 "../x" 会写到 memoryRoot 之外）、含分隔符的非法文件名等问题。
         * 这里只保留「字母/数字/连字符/下划线」 Unicode 字符，其余替换为 '-'，并去首尾连字符；
         * 结果只含合法文件名字符、不含路径分隔符，且对同一输入确定性可逆，保证
         * 「写出去的文件名 ↔ MemoryParser.parse 回读的 name」一致。
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
            }.joinToString("").trim('-')
            // 全非法字符时回退一个占位名，避免空文件名
            return if (cleaned.isBlank()) "memory" else cleaned
        }

        /**
         * 解析 memory 名对应的磁盘文件，并校验解析后的 canonical 路径仍落在 [root] 之内，
         * 杜绝 sanitize 漏网导致的越界写入。
         */
        fun resolveMemoryFile(root: File, name: String): File {
            val safe = sanitizeName(name)
            return File(root, "$safe.md")
        }
    }
}
