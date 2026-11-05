package com.xencode.feature.agent.domain.memory

import java.io.File

/**
 * A single parsed Memory model.
 *
 * @param name Memory name (the unique identifier the model calls it by; usually the file name without extension)
 * @param description Memory description (one-line summary, injected into the system prompt)
 * @param scope Memory scope (GLOBAL or PROJECT)
 * @param file The local file backing this memory
 * @param content Memory body (detailed content with the frontmatter stripped)
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String
)

enum class MemoryScope {
    GLOBAL, PROJECT
}
