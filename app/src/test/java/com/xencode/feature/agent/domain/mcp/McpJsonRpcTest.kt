package com.xencode.feature.agent.domain.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization semantics of the MCP JSON-RPC 2.0 message models.
 *
 * The Json configuration actually used in production lives in StdioTransport / StreamableHttpTransport
 * (ignoreUnknownKeys + encodeDefaults + explicitNulls=false);
 * the tests match it. The default Json (encodeDefaults=false) behavior is verified separately,
 * to ensure the default value (jsonrpc="2.0") is never written out unexpectedly.
 */
class McpJsonRpcTest {

    /** Production configuration matching the MCP transport layer. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** kotlinx.serialization default configuration: encodeDefaults=false. */
    private val defaultJson = Json

    // ---- JsonRpcRequest ----

    @Test
    fun request_roundtrip_keepsAllFields() {
        val request = JsonRpcRequest(
            id = 7,
            method = "tools/call",
            params = JsonObject(mapOf("name" to JsonPrimitive("echo")))
        )

        val decoded = json.decodeFromString<JsonRpcRequest>(json.encodeToString(request))

        assertEquals(request, decoded)
    }

    @Test
    fun request_encodeDefaults_trueWritesJsonrpcVersion() {
        val request = JsonRpcRequest(id = 1, method = "initialize")

        val encoded = json.encodeToString(request)

        assertTrue("the production configuration should write the jsonrpc default value", encoded.contains("\"jsonrpc\":\"2.0\""))
    }

    @Test
    fun request_defaultJson_omitsJsonrpcDefaultValue() {
        val request = JsonRpcRequest(id = 1, method = "initialize")

        val encoded = defaultJson.encodeToString(request)

        assertFalse("default Json must not write default values", encoded.contains("jsonrpc"))
    }

    @Test
    fun request_decodeWithoutJsonrpc_defaultsTo20() {
        val decoded = json.decodeFromString<JsonRpcRequest>("""{"id":1,"method":"ping"}""")

        assertEquals(JSONRPC_VERSION, decoded.jsonrpc)
    }

    @Test
    fun request_nullParams_omittedAndDecodesToNull() {
        val encoded = json.encodeToString(JsonRpcRequest(id = 1, method = "ping"))

        assertFalse("with explicitNulls=false the params key is not written", encoded.contains("params"))
        assertNull(json.decodeFromString<JsonRpcRequest>(encoded).params)
    }

    // ---- JsonRpcNotification ----

    @Test
    fun notification_hasNoId_andOptionalParams() {
        val encoded = json.encodeToString(JsonRpcNotification(method = "notifications/initialized"))

        assertFalse("notifications carry no id", encoded.contains("\"id\""))
        assertFalse("params is not written when null", encoded.contains("params"))

        val decoded = json.decodeFromString<JsonRpcNotification>(
            """{"method":"notifications/cancelled","params":{"requestId":5}}"""
        )
        assertEquals(
            JsonRpcNotification(
                method = "notifications/cancelled",
                params = JsonObject(mapOf("requestId" to JsonPrimitive(5)))
            ),
            decoded
        )
    }

    // ---- JsonRpcResponse ----
    // The data class itself does not enforce the result/error mutual exclusion; it shows up at the serialization layer:
    // with explicitNulls=false the unset side is not written out; on decode both sides are restored independently.

    @Test
    fun response_resultOnly_omitsError() {
        val response = JsonRpcResponse(
            id = 3,
            result = JsonObject(mapOf("ok" to JsonPrimitive(true)))
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"result\""))
        assertFalse("error is not written when null", encoded.contains("\"error\""))

        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)
        assertEquals(response, decoded)
        assertNull(decoded.error)
    }

    @Test
    fun response_errorOnly_omitsResult() {
        val response = JsonRpcResponse(
            id = 3,
            error = JsonRpcError(code = -32601, message = "Method not found")
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"error\""))
        assertFalse("result is not written when null", encoded.contains("\"result\""))

        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)
        assertEquals(response, decoded)
        assertNull(decoded.result)
    }

    @Test
    fun response_nullableId_roundtrip() {
        val noId = json.decodeFromString<JsonRpcResponse>("""{"result":{}}""")
        assertNull(noId.id)

        val withId = json.decodeFromString<JsonRpcResponse>("""{"id":42,"error":{"code":1,"message":"x"}}""")
        assertEquals(42L, withId.id)
        assertEquals(42L, json.decodeFromString<JsonRpcResponse>(json.encodeToString(withId)).id)
    }

    // ---- JsonRpcError ----

    @Test
    fun error_roundtrip_withAndWithoutData() {
        val withData = JsonRpcError(
            code = -32602,
            message = "Invalid params",
            data = JsonPrimitive("bad field")
        )
        assertEquals(withData, json.decodeFromString<JsonRpcError>(json.encodeToString(withData)))

        val withoutData = json.decodeFromString<JsonRpcError>("""{"code":-32000,"message":"boom"}""")
        assertNull(withoutData.data)
    }

    @Test
    fun error_nullData_omittedFromEncoded() {
        val encoded = json.encodeToString(JsonRpcError(code = -32700, message = "Parse error"))

        assertFalse(encoded.contains("data"))
    }

    // ---- McpToolDescriptor ----

    @Test
    fun toolDescriptor_serialName_isInputSchema() {
        val tool = McpToolDescriptor(
            name = "read_file",
            description = "read file",
            inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        )

        val encoded = json.encodeToString(tool)

        assertTrue("the field name must be inputSchema (not the snake_case variant)", encoded.contains("\"inputSchema\""))
        assertFalse(encoded.contains("input_schema"))

        val decoded = json.decodeFromString<McpToolDescriptor>(
            """{"name":"read_file","description":"read file","inputSchema":{"type":"object"}}"""
        )
        assertEquals(tool, decoded)
    }

    @Test
    fun toolDescriptor_wrongKeyName_isNotBound() {
        // Only the key matched by @SerialName("inputSchema") gets bound; input_schema does not
        val decoded = json.decodeFromString<McpToolDescriptor>(
            """{"name":"read_file","input_schema":{"type":"object"}}"""
        )

        assertNull(decoded.inputSchema)
    }

    @Test
    fun toolDescriptor_nullableDescription_roundtrip() {
        val decoded = json.decodeFromString<McpToolDescriptor>("""{"name":"cmd"}""")

        assertNull(decoded.description)
        assertNull(decoded.inputSchema)
    }

    // ---- McpToolsListResult ----

    @Test
    fun toolsListResult_decodeMissingFields_useDefaults() {
        val decoded = json.decodeFromString<McpToolsListResult>("{}")

        assertTrue(decoded.tools.isEmpty())
        assertNull(decoded.nextCursor)
    }

    @Test
    fun toolsListResult_roundtrip_withExplicitValues() {
        val result = McpToolsListResult(
            tools = listOf(McpToolDescriptor(name = "cmd", inputSchema = JsonObject(emptyMap()))),
            nextCursor = "abc123"
        )

        assertEquals(result, json.decodeFromString<McpToolsListResult>(json.encodeToString(result)))
    }

    @Test
    fun toolsListResult_defaultEmptyList_writtenWithEncodeDefaults() {
        val encoded = json.encodeToString(McpToolsListResult())

        assertTrue("with encodeDefaults=true the default empty list is written", encoded.contains("\"tools\":[]"))
        assertFalse("nextCursor is not written when null", encoded.contains("nextCursor"))
    }
}