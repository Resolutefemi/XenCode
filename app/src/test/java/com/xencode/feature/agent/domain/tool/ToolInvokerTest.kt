package com.xencode.feature.agent.domain.tool

import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.workflow.AgentEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * [ToolInvoker] unit tests. Focus: the crash-fix semantics of "tool execution never occupies the caller's thread" —
 * the workflow collector runs on the single Main thread of `viewModelScope.launch`; if tool execution does not switch to IO,
 * the blocking IO of parallel batches (su fork + waitFor / runCommandSync) serially starves the main thread.
 */
class ToolInvokerTest {

    private val toolOutputStore: ToolOutputStore = mockk<ToolOutputStore>(relaxed = true).also { store ->
        // process returns the input unchanged (trimming/persisting is ToolOutputStore's own duty; this file focuses on threading and wrapping semantics)
        every { store.process(any(), any(), any()) } answers { thirdArg() }
    }
    private val invoker = ToolInvoker(toolOutputStore)

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/tmp/ws",
        language = "zh-CN"
    )

    private fun call(name: String = "Bash") = ToolCall(id = "call-1", name = name, arguments = emptyMap())

    private class FakeTool(
        override val name: String,
        private val body: suspend () -> ToolResult
    ) : AgentTool() {
        override val description = "fake"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = body()
    }

    private class FakeStreamTool(
        override val name: String,
        private val events: List<ToolStreamEvent>
    ) : AgentTool(), StreamingAgentTool {
        override val description = "fake"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Error("unused")
        override fun executeStream(
            args: Map<String, JsonElement>,
            context: com.xencode.feature.agent.domain.model.AgentContext
        ) = events.asFlow()
    }

    /**
     * Simulates the production calling environment: the caller is a single-thread "main" dispatcher (mimicking Dispatchers.Main.immediate).
     * Before the fix, tool execution saturated that thread (= ANR/crash); after the fix, everything lands on IO pool threads.
     */
    @Test
    fun runSync_hopsToIoThreadsEvenOnSingleThreadMainDispatcher() = runBlocking {
        val mainSim = Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }
        val dispatcher = mainSim.asCoroutineDispatcher()
        val execThreads = CopyOnWriteArrayList<String>()
        try {
            runBlocking(dispatcher) {
                coroutineScope {
                    (1..4).map { i ->
                        async {
                            invoker.runSync(FakeTool("Bash") {
                                execThreads.add(Thread.currentThread().name)
                                ToolResult.Success(JsonPrimitive("ok$i"))
                            }, call(), context)
                        }
                    }.awaitAll()
                }
            }
        } finally {
            mainSim.shutdownNow()
        }
        assertEquals("should execute 4 times", 4, execThreads.size)
        assertTrue(
            "no tool execution may run on the caller's thread, actual: $execThreads",
            execThreads.none { it.contains("main-sim") }
        )
    }

    /**
     * Parallelism regression: 4 tools each blocking 300ms should finish far faster than the serial 1200ms on the IO pool.
     * Under the old behavior (Main single-thread serial) the total would necessarily be >= 1200ms and the test fails.
     */
    @Test
    fun runSync_parallelBatchesRunConcurrentlyOnIoPool() = runBlocking {
        val startedAt = System.currentTimeMillis()
        runBlocking(Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }.asCoroutineDispatcher()) {
            coroutineScope {
                (1..4).map {
                    async {
                        invoker.runSync(FakeTool("Bash") {
                            Thread.sleep(300)
                            ToolResult.Success(JsonPrimitive("ok"))
                        }, call(), context)
                    }
                }.awaitAll()
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("4 parallel 300ms tools should beat the serial 1200ms, actual ${elapsed}ms", elapsed < 900)
    }

    @Test
    fun runSync_wrapsToolExceptionsAsExecutionFailure() = runBlocking {
        val result = invoker.runSync(FakeTool("Bash") {
            throw IllegalStateException("boom")
        }, call(), context)
        assertTrue(result.isError)
        assertTrue("should contain the error code, actual: ${result.raw}", result.raw.contains("TOOL_EXECUTION_FAILED"))
        assertTrue(result.raw.contains("boom"))
    }

    @Test
    fun runSync_cancellationPassesThroughUnwrapped() {
        val tool = FakeTool("Bash") { throw CancellationException("cancelled") }
        var caught: Throwable? = null
        runBlocking {
            try {
                invoker.runSync(tool, call(), context)
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("CancellationException must be rethrown", caught is CancellationException)
    }

    @Test
    fun runSync_unknownToolReturnsNotFoundError() = runBlocking {
        val result = invoker.runSync(null, call(name = "nonexistent-tool"), context)
        assertTrue(result.isError)
        assertTrue(result.raw.contains("TOOL_NOT_FOUND"))
    }

    /** sendFile result: attachments carry the host-local path; the transport string fed to the model strips local_path. */
    @Test
    fun runSync_extractsAttachmentsAndStripsLocalPaths() = runBlocking {
        val files = JsonArray(listOf(JsonObject(mapOf(
            "path" to JsonPrimitive("/root/workspace/a.txt"),
            "local_path" to JsonPrimitive("/data/user/0/com.xencode/files/ws/a.txt"),
            "name" to JsonPrimitive("a.txt"),
            "mime_type" to JsonPrimitive("text/plain"),
            "size_bytes" to JsonPrimitive(12),
            "is_image" to JsonPrimitive(false)
        ))))
        val result = invoker.runSync(
            FakeTool("sendFile") {
                ToolResult.Success(JsonObject(mapOf("files" to files)))
            },
            call(name = "sendFile"),
            context
        )
        assertFalse(result.isError)
        assertEquals(1, result.attachments.size)
        assertEquals("/data/user/0/com.xencode/files/ws/a.txt", result.attachments[0].localPath)
        assertTrue("the model side must not see local_path", !result.raw.contains("local_path"))
        assertTrue("should carry the container path", result.raw.contains("/root/workspace/a.txt"))
        assertTrue("should flag files as attached", result.raw.contains("files_attached"))
    }

    @Test
    fun runStream_forwardsProgressEventsAndEndsWithNonErrorResult() = runBlocking {
        val progressThreads = CopyOnWriteArrayList<String>()
        val events = listOf(
            ToolStreamEvent.Progress(JsonPrimitive("line-1").toString()),
            ToolStreamEvent.Progress(JsonPrimitive("line-2").toString()),
            ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive("done")))
        )
        val result = invoker.runStream(
            FakeStreamTool("Bash", events),
            call(),
            context
        ) { event ->
            if (event is AgentEvent.ToolCallProgress) {
                progressThreads.add(Thread.currentThread().name)
            }
        }
        assertFalse(result.isError)
        assertTrue(result.raw.contains("done"))
        assertTrue("progress events should be delivered at least once", progressThreads.isNotEmpty())
    }

    @Test
    fun runStream_alsoDoesNotOccupyCallerSingleThread() = runBlocking {
        val mainSim = Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }
        val eventThreads = CopyOnWriteArrayList<String>()
        try {
            runBlocking(mainSim.asCoroutineDispatcher()) {
                invoker.runStream(
                    FakeStreamTool("Bash", listOf(
                        ToolStreamEvent.Progress(JsonPrimitive("x").toString()),
                        ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive("done")))
                    )),
                    call(),
                    context
                ) { event ->
                    eventThreads.add(Thread.currentThread().name)
                }
            }
        } finally {
            mainSim.shutdownNow()
        }
        assertTrue(eventThreads.isNotEmpty())
        assertTrue(
            "neither streaming execution nor event delivery may occupy the caller's thread, actual: $eventThreads",
            eventThreads.none { it.contains("main-sim") }
        )
    }

    @Test
    fun runStream_missingCompletedResultReportsMissingError() = runBlocking {
        val result = invoker.runStream(
            FakeStreamTool("Bash", listOf(ToolStreamEvent.Progress(JsonPrimitive("x").toString()))),
            call(),
            context
        ) { }
        assertTrue(result.isError)
        assertTrue(result.raw.contains("MISSING_STREAM_RESULT"))
    }
}
