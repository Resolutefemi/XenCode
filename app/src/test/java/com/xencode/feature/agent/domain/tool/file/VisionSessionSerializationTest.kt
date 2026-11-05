package com.xencode.feature.agent.domain.tool.file

import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that vision session history ([AgentMessage] polymorphic sealed class, with images) round-trips fully through JSON persistence.
 * VisionSessionStore depends on this serialization; a sealed class base missing @Serializable throws
 * "Serializer for class 'AgentMessage' is not found" at runtime, so this uses the same Json config as the store for a round-trip check.
 */
class VisionSessionSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun messageHistory_roundTripsWithImages() {
        val messages = listOf(
            AgentMessage.UserMessage(
                content = "Please compare the differences between these two images",
                images = listOf(
                    AgentImage(mimeType = "image/jpeg", base64Data = "AAAA", path = "/a.jpg"),
                    AgentImage(mimeType = "image/png", base64Data = "BBBB", path = "/b.png")
                )
            ),
            AgentMessage.AssistantMessage(content = "The left image is A, the right is B; the main difference is the color scheme."),
            AgentMessage.UserMessage(content = "What is in the top-right corner of the left image?"),
            AgentMessage.AssistantMessage(content = "It is a red button.")
        )

        val restored = json.decodeFromString<List<AgentMessage>>(json.encodeToString(messages))

        assertEquals(messages.size, restored.size)
        assertEquals(messages, restored)
    }
}
