package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 900_000,
    )

    @Test
    fun unknownWindowStillRunsButNeverAutoCompacts() {
        for (window in listOf(null, 0, -1)) {
            var summaries = 0
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("摘要")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("完成")
                    }
                })
            assertEquals("完成", result.content)
            assertEquals(0, summaries)
        }
    }

    @Test
    fun automaticCompactionUsesOnlyActualInputUsageAndConfiguredWindow() {
        for (input in listOf(null, 300_000, 764_999, 765_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(config, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        assertTrue(request.messages.toString().contains("长".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            assertEquals(if (input == 765_000) 1 else 0, summaries)
            assertEquals("完成", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 765_000) {
                assertEquals(765_000, completed!!.tokensBefore)
                assertNull(completed.tokensAfter)
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("此前任务已完成。")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("完成")
            }
        }
        val answer = AgentModelClient.complete(disabled, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("完成", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNull(answer.contextSnapshot)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("历史已完成。")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun invalidSummaryDoesNotRetryAndKeepsOriginalHistory() {
        for ((content, stop) in listOf("" to "stop", "半截" to "length", "长".repeat(12_001) to "stop")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("不应提交无效摘要") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("完成")
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals(2, requests)
        assertNull(loop.contextSnapshot())
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("不应提交")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    private fun history() = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(780_000)),
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
