package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBatchToolTest {
    @Test
    fun wrapperRunsIndependentQueriesInParallelButReturnsOrderedSingleToolResult() {
        val bothStarted = CountDownLatch(2)
        val secondFinished = CountDownLatch(1)
        val events = mutableListOf<AgentEvent>()
        val eventThreads = mutableSetOf<Thread>()
        val provider = ScriptedProvider(wrapper(listOf(query("first"), query("second"))))
        val result = AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
            assertEquals("search_apps", call.name)
            val query = JSONObject(call.argumentsJson).getString("query")
            bothStarted.countDown()
            assertTrue("Both reads must start before either completes", bothStarted.await(5, TimeUnit.SECONDS))
            if (query == "first") assertTrue(secondFinished.await(5, TimeUnit.SECONDS)) else secondFinished.countDown()
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("query", query).toString())
        }, provider = provider, onEvent = {
            events += it
            eventThreads += Thread.currentThread()
        })
        val aggregate = provider.toolResult()
        assertTrue(aggregate.getBoolean("ok"))
        assertEquals(listOf("first", "second"), aggregate.getJSONArray("results").objects().map {
            it.getJSONObject("result").getString("query")
        })
        assertEquals(listOf("batch-call"), provider.requests.last().messages.objects()
            .filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") })
        assertTrue(provider.requests.last().messages.objects().any { it.has("_eta_batch_checkpoint") })
        assertFalse(OpenAiRequestMessages.forChatCompletions(provider.requests.last().messages)
            .objects().any { it.has("_eta_batch_checkpoint") })
        assertEquals(listOf("batch", "search_apps", "search_apps"), events.filterIsInstance<AgentEvent.ToolStarted>().map { it.name })
        assertEquals(setOf(Thread.currentThread()), eventThreads)
        assertEquals("完成", result.content)
    }

    @Test
    fun nativeCallsAreParallelAndResultsArePersistedInSourceOrder() {
        val bothStarted = CountDownLatch(2)
        val provider = ScriptedProvider(callsResponse(listOf(
            tool("first", "search_apps", "{\"query\":\"a\"}"),
            tool("second", "search_apps", "{\"query\":\"b\"}"),
        )))
        val result = AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
            bothStarted.countDown()
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult("{\"ok\":true,\"id\":\"${call.id}\"}")
        }, provider = provider)
        assertEquals(listOf("first", "second"), result.transcript.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(listOf("first", "second"), provider.requests.last().messages.objects()
            .filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") })
    }

    @Test
    fun sequentialModeRunsOneQueryAtATimeAndPermissionFailuresRemainPerItem() {
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(wrapper(listOf(query("denied"), query("allowed")), "sequential", "continue"))
        AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
            assertEquals(Thread.currentThread(), loopThread)
            val query = JSONObject(call.argumentsJson).getString("query")
            executed += query
            AgentModelClient.ToolResult(if (query == "denied") "{\"ok\":false,\"code\":\"PERMISSION_DENIED\"}" else "{\"ok\":true}")
        }, provider = provider)
        val aggregate = provider.toolResult()
        assertEquals(listOf("denied", "allowed"), executed)
        assertFalse(aggregate.getBoolean("ok"))
        assertEquals("PERMISSION_DENIED", aggregate.getJSONArray("results").getJSONObject(0).getJSONObject("result").getString("code"))
        assertTrue(aggregate.getJSONArray("results").getJSONObject(1).getBoolean("ok"))
    }

    @Test
    fun malformedSubcallRejectsEntireBatchBeforeAnyQueryExecutes() {
        val provider = ScriptedProvider(wrapper(listOf(query("valid"), item("search_apps", JSONObject()))))
        val executed = AtomicInteger()
        AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor {
            executed.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider)
        assertEquals(0, executed.get())
        assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
        assertTrue(provider.toolResult().getString("message").startsWith("calls[1]:"))
    }

    @Test
    fun autoModeCannotAdmitWritesGuiMcpNestedOrUnavailableTools() {
        for (name in listOf("write_file", "tap", "launch_app", "terminal", "mcp_example", "batch", "web_search")) {
            val provider = ScriptedProvider(wrapper(listOf(query("valid"), item(name, JSONObject()))))
            val executed = AtomicInteger()
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor {
                executed.incrementAndGet()
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, provider = provider)
            assertEquals(name, 0, executed.get())
            assertEquals(name, "INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
        }
    }

    @Test
    fun sequentialModeAdmitsGuiFilesTerminalAndMcpAndRunsOnLoopThreadInOrder() {
        val executed = mutableListOf<String>()
        val calls = listOf(
            item("launch_app", JSONObject().put("package_name", "com.example.app")),
            write("done"),
            item("read_file", JSONObject().put("path", "note.txt")),
            item("tap", JSONObject().put("x", 20).put("y", 30)),
            item("terminal", JSONObject().put("action", "exec").put("command", "true")),
            item("mcp_example", JSONObject().put("value", "known")),
        )
        val provider = ScriptedProvider(wrapper(calls, "sequential"))
        AgentModelClient.complete(config().copy(terminalTools = true), "执行", AgentModelClient.ToolExecutor { call ->
            assertEquals(loopThread, Thread.currentThread())
            if (call.name == "read_file") assertEquals(listOf("launch_app", "write_file"), executed)
            executed += call.name
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider, additionalTools = JSONArray().put(mcpSchema()))
        assertEquals(calls.map { it.getString("tool") }, executed)
        assertTrue("mcp_example" in provider.requests.first().tools.batchNames())
        assertTrue(provider.toolResult().getBoolean("ok"))
        assertEquals(6, provider.toolResult().getInt("total"))
    }

    @Test
    fun sequentialFailureSkipsLaterMutationsByDefaultAndContinueMustBeExplicit() {
        for (onError in listOf(null, "continue")) {
            val executed = mutableListOf<String>()
            val provider = ScriptedProvider(wrapper(listOf(write("fail"), write("later")), "sequential", onError))
            val result = AgentModelClient.complete(config().copy(terminalTools = true), "执行", AgentModelClient.ToolExecutor { call ->
                val content = JSONObject(call.argumentsJson).getString("content")
                executed += content
                AgentModelClient.ToolResult(if (content == "fail") "{\"ok\":false,\"code\":\"PERMISSION_DENIED\"}" else "{\"ok\":true}")
            }, provider = provider)
            assertEquals(if (onError == null) listOf("fail") else listOf("fail", "later"), executed)
            assertFalse(provider.toolResult().getBoolean("ok"))
            val remaining = provider.toolResult().getJSONArray("results").getJSONObject(1)
            assertEquals(onError != null, remaining.getBoolean("ok"))
            if (onError == null) assertEquals("BATCH_STOPPED_ON_ERROR", remaining.getJSONObject("result").getString("code"))
            val durable = JSONObject(result.transcript.single { it.role == "tool" }.content)
            assertEquals(if (onError == null) "skipped" else "completed", durable.getJSONArray("results").getJSONObject(1).getString("status"))
        }
    }

    @Test
    fun sequentialPreflightRejectsInvalidLaterMutationBeforeEarlierMutationExecutes() {
        val provider = ScriptedProvider(wrapper(listOf(write("first"), item("write_file", JSONObject().put("path", "note.txt"))), "sequential"))
        val executed = AtomicInteger()
        AgentModelClient.complete(config().copy(terminalTools = true), "执行", AgentModelClient.ToolExecutor {
            executed.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider)
        assertEquals(0, executed.get())
        assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
        assertTrue(provider.toolResult().getString("message").startsWith("calls[1]:"))
    }

    @Test
    fun sequentialModeCannotBypassDisabledRestrictedRootOrNestedToolBoundaries() {
        val cases = listOf(
            Triple(write("disabled"), config(), null),
            Triple(item("tap", JSONObject().put("x", 1).put("y", 1)), config(), setOf("batch", "search_apps")),
            Triple(item("read_file", JSONObject().put("path", "note.txt").put("identity", "root")), config().copy(terminalTools = true), null),
            Triple(item("virtual_screen", JSONObject().put("action", "restart")), config(), null),
            Triple(item("mcp_example", JSONObject().put("value", "known")), config(), null),
            Triple(item("batch", JSONObject().put("calls", JSONArray().put(query("nested")))), config(), null),
        )
        for ((call, config, restriction) in cases) {
            val provider = ScriptedProvider(wrapper(listOf(query("first"), call), "sequential"))
            val executed = AtomicInteger()
            AgentModelClient.complete(config, "执行", AgentModelClient.ToolExecutor {
                executed.incrementAndGet()
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, provider = provider, restrictedToolNames = restriction,
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) })
            assertEquals(call.getString("tool"), 0, executed.get())
            assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
        }
    }

    @Test
    fun sequentialCancellationPreservesCompletedInFlightAndUntouchedStatesWithoutSecrets() {
        val controller = AgentRunController()
        val publications = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        val provider = ScriptedProvider(wrapper(listOf(write("secret-a"), write("secret-b"), write("secret-c")), "sequential"))
        val executed = mutableListOf<String>()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config().copy(terminalTools = true), "执行", AgentModelClient.ToolExecutor { call ->
                val content = JSONObject(call.argumentsJson).getString("content")
                executed += content
                if (executed.size == 2) {
                    controller.cancel()
                    controller.throwIfCancelled()
                }
                AgentModelClient.ToolResult("{\"ok\":true,\"text\":\"secret-output\"}")
            }, provider = provider, runController = controller, onTranscript = publications::add)
        }
        assertTrue(failure.cause is AgentRunCancelledException)
        assertEquals(listOf("secret-a", "secret-b"), executed)
        val durable = JSONObject(failure.transcript.single { it.role == "tool" }.content)
        assertEquals("TOOL_INTERRUPTED", durable.getString("code"))
        assertEquals(listOf("completed", "unknown", "not_started"), durable.getJSONArray("results").objects().map { it.getString("status") })
        assertTrue(publications.any { messages -> messages.any { it.role == "tool" && it.content.contains("completed") } })
        (publications + listOf(failure.transcript)).forEach { assertFalse(it.toString().contains("secret-")) }
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun sequentialGuiPauseSkipsRemainingMutationAndRecoversOnceEvenWithContinue() {
        val provider = ScriptedProvider(wrapper(listOf(
            write("first"), item("tap", JSONObject().put("x", 1).put("y", 1)), write("skipped"),
        ), "sequential", "continue"))
        val executed = mutableListOf<String>()
        val recoveries = AtomicInteger()
        AgentModelClient.complete(config().copy(terminalTools = true), "执行", AgentModelClient.ToolExecutor { call ->
            executed += call.name
            if (call.name == "tap") AgentModelClient.ToolResult("{\"ok\":false}",
                stop = AgentModelClient.ToolStop("UI_NO_PROGRESS", "暂停"))
            else AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider, recoverVirtualUi = {
            recoveries.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        })
        assertEquals(listOf("write_file", "tap", "observe_screen"), executed)
        assertEquals(1, recoveries.get())
        val aggregate = provider.requests.last().messages.objects().single { it.optString("tool_call_id") == "batch-call" }
            .getString("content").let(::JSONObject)
        assertEquals("UI_EXECUTION_PAUSED", aggregate.getJSONArray("results").getJSONObject(2).getJSONObject("result").getString("code"))
    }

    @Test
    fun autoStopOnErrorRunsReadsSequentiallyAndRejectsEnabledMutationsBeforeExecution() {
        val provider = ScriptedProvider(wrapper(listOf(query("fail"), query("skipped")), onError = "stop"))
        val executed = mutableListOf<String>()
        AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
            assertEquals(loopThread, Thread.currentThread())
            executed += JSONObject(call.argumentsJson).getString("query")
            AgentModelClient.ToolResult("{\"ok\":false}")
        }, provider = provider)
        assertEquals(listOf("fail"), executed)
        val mutationProvider = ScriptedProvider(wrapper(listOf(query("first"), write("mutation"))))
        val mutations = AtomicInteger()
        AgentModelClient.complete(config().copy(terminalTools = true), "查询", AgentModelClient.ToolExecutor {
            mutations.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = mutationProvider)
        assertEquals(0, mutations.get())
        assertTrue(mutationProvider.toolResult().getString("message").contains("mode=sequential"))
    }

    @Test
    fun catalogIncludesAllAvailableToolsAndHonorsHostedSearchAndRestriction() {
        val provider = ScriptedProvider(wrapper(listOf(query("valid"))))
        AgentModelClient.complete(config().copy(browserTools = true), "查询", AgentModelClient.ToolExecutor {
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider, restrictedToolNames = setOf("batch", "search_apps"))
        assertEquals(setOf("search_apps"), provider.requests.first().tools.batchNames())
        val hosted = AgentToolCatalog.build(terminalTools = false, browserTools = true, localWebSearch = false)
        assertFalse("web_search" in hosted.batchNames())
        assertTrue("fetch_url" in hosted.batchNames())
        assertFalse("read_file" in hosted.batchNames())
        assertTrue("read_file" in AgentToolCatalog.build(terminalTools = true, browserTools = false).batchNames())
        assertTrue("write_file" in AgentToolCatalog.build(terminalTools = true, browserTools = false).batchNames())
        assertTrue("tap_area" in hosted.batchNames())
        assertFalse("batch" in hosted.batchNames())
        val restricted = AgentBatchToolCatalog.project(JSONArray().put(
            AgentToolSchema.function("observe_screen", "", JSONObject().put("type", "object"))))
        assertTrue(restricted.objects().any { it.getJSONObject("function").getString("name") == "batch" })
    }

    @Test
    fun wrapperCannotBypassUnprivilegedFileSchema() {
        val provider = ScriptedProvider(wrapper(listOf(item("read_file", JSONObject().put("path", "/data/private").put("identity", "root")))))
        val executed = AtomicInteger()
        AgentModelClient.complete(config().copy(terminalTools = true), "查询", AgentModelClient.ToolExecutor {
            executed.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider)
        assertEquals(0, executed.get())
        assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
    }

    @Test
    fun sizeLimitAndInvalidModeAreRejectedWithoutExecutingQueries() {
        for (arguments in listOf(
            JSONObject().put("calls", JSONArray()),
            JSONObject().put("calls", JSONArray(List(9) { query("$it") })),
            JSONObject().put("calls", JSONArray().put(query("a"))).put("mode", "unbounded"),
            JSONObject().put("calls", JSONArray().put(query("a"))).put("on_error", "retry"),
        )) {
            val provider = ScriptedProvider(callsResponse(listOf(tool("batch-call", "batch", arguments.toString()))))
            val executed = AtomicInteger()
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor {
                executed.incrementAndGet()
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, provider = provider)
            assertEquals(0, executed.get())
            assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
        }
    }

    @Test
    fun mutationIsABarrierBetweenNativeReadGroups() {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val wrote = AtomicInteger()
        val provider = ScriptedProvider(callsResponse(listOf(
            tool("read-a", "read_file", "{\"path\":\"/a\"}"),
            tool("read-b", "read_file", "{\"path\":\"/b\"}"),
            tool("write", "write_file", "{\"path\":\"/a\",\"content\":\"new\"}"),
            tool("read-c", "read_file", "{\"path\":\"/a\"}"),
            tool("read-d", "read_file", "{\"path\":\"/b\"}"),
        )))
        AgentModelClient.complete(config().copy(terminalTools = true), "查询", AgentModelClient.ToolExecutor { call ->
            when (call.id) {
                "write" -> {
                    assertEquals(setOf("read-a", "read-b"), finished.toSet())
                    wrote.incrementAndGet()
                }
                "read-c", "read-d" -> assertEquals(1, wrote.get())
            }
            finished += call.id
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider)
        assertEquals(setOf("read-a", "read-b", "write", "read-c", "read-d"), finished.toSet())
    }

    @Test
    fun cancellationStopsRunningWorkersAndDoesNotStartNextWave() {
        val controller = AgentRunController()
        val fourStarted = CountDownLatch(4)
        val started = Collections.synchronizedSet(mutableSetOf<Int>())
        val provider = ScriptedProvider(wrapper(List(8) { query("$it") }))
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
                val index = JSONObject(call.argumentsJson).getString("query").toInt()
                started += index
                fourStarted.countDown()
                assertTrue(fourStarted.await(5, TimeUnit.SECONDS))
                if (index == 0) controller.cancel() else CountDownLatch(1).await(5, TimeUnit.SECONDS)
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, provider = provider, runController = controller)
        }
        assertTrue(failure.cause is AgentRunCancelledException)
        assertEquals(setOf(0, 1, 2, 3), started.toSet())
        assertEquals(1, provider.requests.size)
        val toolResults = failure.transcript.filter { it.role == "tool" }
        assertEquals(listOf("batch-call"), toolResults.map { it.toolCallId })
        assertTrue(toolResults.single().content.contains("TOOL_INTERRUPTED"))
    }

    @Test
    fun cancellationPreservesCompletedNativeResultAndClosesRemainingCall() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(callsResponse(listOf(
            tool("first", "search_apps", "{\"query\":\"a\"}"),
            tool("second", "search_apps", "{\"query\":\"b\"}"),
        )))
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
                if (call.id == "second") CountDownLatch(1).await(5, TimeUnit.SECONDS)
                AgentModelClient.ToolResult("{\"ok\":true,\"completed\":true}")
            }, provider = provider, runController = controller, onTranscript = { transcript ->
                if (transcript.any { it.role == "tool" && it.toolCallId == "first" }) controller.cancel()
            })
        }
        assertTrue(failure.cause is AgentRunCancelledException)
        val results = failure.transcript.filter { it.role == "tool" }
        assertEquals(listOf("first", "second"), results.map { it.toolCallId })
        assertTrue(results.first().content.contains("completed"))
        assertTrue(results.last().content.contains("TOOL_INTERRUPTED"))
    }

    @Test
    fun batchArgumentsAndResultsAreRedactedAndParentSummaryOnlyContainsCounts() {
        val secret = "private-query-9021"
        val secretOutput = "private-output-8276"
        val publications = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(wrapper(listOf(query(secret))))
        val result = AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor {
            AgentModelClient.ToolResult("{\"ok\":true,\"text\":\"$secretOutput\"}", sensitive = true)
        }, provider = provider, onTranscript = publications::add, onEvent = events::add)
        assertTrue(provider.toolResult().toString().contains(secretOutput))
        (publications + listOf(result.transcript)).forEach { transcript ->
            assertFalse(transcript.toString().contains(secret))
            assertFalse(transcript.toString().contains(secretOutput))
        }
        val summary = events.filterIsInstance<AgentEvent.ToolFinished>().single { it.name == "batch" }.resultSummary
        assertEquals("批量执行 · 1/1 项成功", summary)
        assertFalse(events.filterIsInstance<AgentEvent.ToolStarted>().single { it.name == "batch" }.argsPreview.contains(secret))
    }

    @Test
    fun allEightQueriesCompleteWithAtMostFourWorkersAndErrorsDoNotDiscardOtherResults() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstWave = CountDownLatch(4)
        val provider = ScriptedProvider(wrapper(List(8) { query("$it") }))
        AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
            val index = JSONObject(call.argumentsJson).getString("query").toInt()
            val running = active.incrementAndGet()
            peak.accumulateAndGet(running, ::maxOf)
            try {
                if (index < 4) {
                    firstWave.countDown()
                    assertTrue(firstWave.await(5, TimeUnit.SECONDS))
                }
                if (index == 1) throw IllegalStateException("query failed")
                AgentModelClient.ToolResult("{\"ok\":true,\"index\":$index}")
            } finally {
                active.decrementAndGet()
            }
        }, provider = provider)
        assertEquals(4, peak.get())
        assertEquals(0, active.get())
        val aggregate = provider.toolResult()
        assertFalse(aggregate.getBoolean("ok"))
        assertEquals(8, aggregate.getInt("total"))
        val results = aggregate.getJSONArray("results").objects()
        assertEquals((0 until 8).toList(), results.map { it.getInt("index") })
        assertEquals("TOOL_ERROR", results[1].getJSONObject("result").getString("code"))
        assertEquals(7, results.count { it.getBoolean("ok") })
    }

    @Test
    fun trustedPauseInsideSequentialWrapperSkipsRemainingReadsAndReachesExistingRecovery() {
        val provider = ScriptedProvider(wrapper(listOf(query("first"), query("skipped")), "sequential"))
        val executed = mutableListOf<String>()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
                executed += JSONObject(call.argumentsJson).getString("query")
                AgentModelClient.ToolResult("{\"ok\":false}",
                    stop = AgentModelClient.ToolStop("UI_NO_PROGRESS", "暂停"))
            }, provider = provider)
        }
        assertEquals(listOf("first"), executed)
        assertTrue(failure.cause is AgentUiExecutionPausedException)
        assertEquals(1, provider.requests.size)
        assertEquals(listOf("batch-call"), failure.transcript.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test
    fun cancellingSequentialWrapperDoesNotExecuteRemainingQueryOrReplayIt() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(wrapper(listOf(query("first"), query("skipped")), "sequential"))
        val executed = mutableListOf<String>()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor { call ->
                executed += JSONObject(call.argumentsJson).getString("query")
                controller.cancel()
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, provider = provider, runController = controller)
        }
        assertEquals(listOf("first"), executed)
        assertTrue(failure.cause is AgentRunCancelledException)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun explicitRestrictionCanDisableWrapperEvenWhenQueriesRemainAvailable() {
        val provider = ScriptedProvider(wrapper(listOf(query("valid"))))
        val executed = AtomicInteger()
        AgentModelClient.complete(config(), "查询", AgentModelClient.ToolExecutor {
            executed.incrementAndGet()
            AgentModelClient.ToolResult("{\"ok\":true}")
        }, provider = provider, restrictedToolNames = setOf("search_apps"))
        assertEquals(0, executed.get())
        assertFalse(provider.requests.first().tools.objects().any { it.getJSONObject("function").getString("name") == "batch" })
        assertEquals("INVALID_TOOL_ARGUMENTS", provider.toolResult().getString("code"))
    }

    private val loopThread = Thread.currentThread()

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test-key", model = "test-model",
        contextWindow = 128_000, systemPrompt = "", browserTools = false,
        openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
    )

    private fun query(query: String) = item("search_apps", JSONObject().put("query", query))
    private fun write(content: String) = item("write_file", JSONObject().put("path", "note.txt").put("content", content))
    private fun mcpSchema() = AgentToolSchema.function("mcp_example", "", JSONObject().put("type", "object")
        .put("properties", JSONObject().put("value", JSONObject().put("type", "string")))
        .put("required", JSONArray().put("value")).put("additionalProperties", false))
    private fun item(name: String, args: JSONObject) = JSONObject().put("tool", name).put("arguments", args)
    private fun wrapper(calls: List<JSONObject>, mode: String = "auto", onError: String? = null) = callsResponse(listOf(
        tool("batch-call", "batch", JSONObject().put("calls", JSONArray(calls)).put("mode", mode)
            .also { if (onError != null) it.put("on_error", onError) }.toString()),
    ))
    private fun tool(id: String, name: String, args: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args))
    private fun callsResponse(calls: List<JSONObject>) = JSONObject().put("role", "assistant").put("content", "")
        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls))

    private class ScriptedProvider(private val first: JSONObject) : AgentProviderClient {
        override val id = "scripted"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS, streamingText = true, streamingToolCalls = true,
            imageInput = true, toolResultImages = false, strictTools = false, parallelToolCalls = false,
        )
        data class Request(val messages: JSONArray, val tools: JSONArray)
        val requests = mutableListOf<Request>()
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            requests += Request(JSONArray(request.messages.toString()), JSONArray(request.tools.toString()))
            return ProviderResponse(if (requests.size == 1) first else JSONObject().put("role", "assistant")
                .put("content", "完成").put("finish_reason", "stop"))
        }
        fun toolResult(): JSONObject = JSONObject(requests.last().messages.objects()
            .last { it.optString("role") == "tool" }.getString("content"))
    }

    private fun JSONArray.batchNames(): Set<String> = objects().single { it.getJSONObject("function").getString("name") == "batch" }
        .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("calls").getJSONObject("items").getJSONObject("properties")
        .getJSONObject("tool").getJSONArray("enum").let { names -> (0 until names.length()).map { names.getString(it) }.toSet() }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
