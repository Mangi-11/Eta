package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactionTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
    )

    @Test
    fun unknownWindowDoesNotCompactBecauseHistoryExceedsLegacyStorageLimit() {
        val original = "历史事实".repeat(40_000)
        var requests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", original)),
            provider = provider { request, _ ->
                requests++
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                assertTrue(request.messages.toString().contains(original))
                response("完成")
            })
        assertEquals(1, requests)
        assertNull(result.contextSnapshot)
    }

    @Test
    fun interruptedToolBatchKeepsCompletedResultAndMarksOnlyUnknownResults() {
        val call = AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
            {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]""")
        val completed = AgentModelClient.ConversationMessage("tool", "真实完成结果", toolCallId = "a")
        val restored = AgentToolBatchRecovery.completeInterrupted(listOf(call, completed))
        assertEquals(completed, restored[1])
        assertEquals("b", restored.last().toolCallId)
        assertTrue(restored.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals(restored, AgentToolBatchRecovery.completeInterrupted(restored))
    }

    @Test
    fun automaticCompactionPreservesIncrementalTranscriptAndLatestRequest() {
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "第 $turn 个任务"),
            AgentModelClient.ConversationMessage("assistant", "事实 $turn ".repeat(2200)),
        ) }
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertEquals(0, request.effectiveTools.length())
                response("已完成前面的任务，继续处理最新请求。")
            } else {
                assertTrue(request.messages.toString().contains("最新请求必须保留"))
                assertTrue(request.messages.toString().contains("固定约束"))
                assertTrue(request.messages.toString().contains("Eta 上下文摘要"))
                response("完成最新请求")
            }
        }
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 50_000), "最新请求必须保留", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history, provider = provider, onContextSnapshot = snapshots::add, onEvent = events::add,
        )
        assertTrue(summaries > 0)
        assertEquals(listOf("完成最新请求"), result.transcript.map { it.content })
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals("完成最新请求", snapshot.messages.last().content)
        assertEquals(9, snapshot.consumedUserTurns)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "completed" })
        assertTrue(snapshots.all { AgentContextSnapshot.decode(it.encode()) == it })
    }

    @Test
    fun manualCompactionDoesNotCreateUserOrAssistantTranscript() {
        val result = AgentModelClient.complete(
            config, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), compactOnly = true, provider = provider { request, _ ->
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("历史工作已完成，保留后续问题。")
            },
        )
        assertTrue(result.transcript.isEmpty())
        assertEquals("", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.messages.none { it.role == "user" && it.content.isBlank() })
    }

    @Test
    fun truncatedSummaryNeverReplacesOriginalContext() {
        val original = jsonHistory()
        val before = original.toString()
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            response("半截摘要", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })
        assertThrows(AgentModelFailure::class.java) { session.compact(JSONArray(), force = true) }
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun cancellationAndPersistenceFailureKeepOriginalContext() {
        for (cancel in listOf(true, false)) {
            val original = jsonHistory()
            val before = original.toString()
            val controller = AgentRunController()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
                if (cancel) controller.cancel()
                response("有界摘要")
            }, controller, { emptySet() }, {}, { throw IllegalStateException("fixture storage failure") })
            assertThrows(Exception::class.java) { session.compact(JSONArray(), force = true) }
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun summaryInputRedactsSensitiveToolsAndOpaqueReasoningAndImages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
        messages.put(AgentConversationCodec.userTextMessage("旧任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取结果")
            .put("reasoning_content", "PRIVATE_THINKING")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "sensitive").put("type", "function")
                .put("function", JSONObject().put("name", "read_secret").put("arguments", "SECRET_ARGUMENT"))))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("encrypted", "OPAQUE_SECRET")))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "sensitive").put("content", "SECRET_RESULT"))
        history().forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val pending = AgentConversationCodec.userMessage("瞬时观察", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_IMAGE", "image/png", 10),
        )).put("_eta_observation", true)
        messages.put(pending)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            val text = request.messages.toString()
            listOf("SECRET_ARGUMENT", "SECRET_RESULT", "PRIVATE_THINKING", "OPAQUE_SECRET", "PRIVATE_IMAGE").forEach {
                assertFalse("摘要包含敏感数据 $it", text.contains(it))
            }
            response("已读取过信息，原始敏感结果未保留。")
        }, AgentRunController()).compact(messages, 1, setOf("sensitive"), force = true)
        assertSame(pending, compacted.getJSONObject(compacted.length() - 1))
        assertFalse(compacted.toString().contains("OPAQUE_SECRET"))
    }

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
            listOf("a", "b").forEach { put(JSONObject().put("id", it).put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))) }
        })
        val messages = listOf(call,
            JSONObject().put("role", "tool").put("tool_call_id", "a"),
            JSONObject().put("role", "tool").put("tool_call_id", "b"),
            AgentConversationCodec.userTextMessage("继续"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun budgetUsesModelWindowAndUsageCalibrationWithoutCountingImageBase64() {
        val budget = AgentContextBudget(10_000)
        assertFalse(budget.shouldCompact(8499))
        assertTrue(budget.shouldCompact(8500))
        assertFalse(budget.shouldCompact(0))
        assertFalse(AgentContextBudget(null).shouldCompact(Int.MAX_VALUE))
        assertEquals(4, AgentContextBudget.textTokens("中文测试"))
        assertEquals(2, AgentContextBudget.textTokens("abcdef"))
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("文本"))
        val base = budget.estimate(messages, JSONArray())
        budget.observe(AgentTokenUsage(inputTokens = base * 2), base)
        assertEquals(base * 2, budget.estimate(messages, JSONArray()))
        assertTrue(AgentContextBudget.rawEstimate(messages, JSONArray().put("tool".repeat(100))) > base)
        fun image(bytes: Int) = JSONArray().put(AgentConversationCodec.userMessage("图片", listOf(
            AgentModelClient.ModelImage("data:image/png;base64," + "A".repeat(bytes), "image/png", bytes),
        )))
        assertEquals(AgentContextBudget.rawEstimate(image(10)), AgentContextBudget.rawEstimate(image(10000)))
    }

    @Test
    fun overflowClassificationDoesNotTreatEveryBadRequestAsCapacityFailure() {
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"code":"context_length_exceeded","message":"private fixture"}}""").code)
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"type":"invalid_request_error","message":"prompt is too long: fixture"}}""").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400,
            """{"error":{"message":"invalid tool parameter"}}""").code)
    }

    @Test
    fun overflowRecoveryCompactsBeforeRetryAndDiscardsFailedThinking() {
        var normalRequests = 0
        var summaryRequests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("之前的任务已完成。")
                } else if (normalRequests++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败思考"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                } else response("完成")
            })
        assertEquals(2, normalRequests)
        assertTrue(summaryRequests > 0)
        assertEquals("", result.reasoningContent)
        assertEquals(listOf("完成"), result.transcript.map { it.content })
    }

    @Test
    fun hostedToolOverflowDoesNotReplayOrStartCompaction() {
        var requests = 0
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "查询", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                })
        }
        assertEquals(1, requests)
        assertFalse((error.cause as AgentModelFailure).recoveryAllowed)
    }

    @Test
    fun summaryOverflowSplitsHistoryWithoutDroppingGroups() {
        var requests = 0
        val seen = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { request, _ ->
            requests++
            val historyText = request.messages.getJSONObject(1).getString("content")
            if (Regex("问题 [1-4]").findAll(historyText).count() > 2) {
                throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
            }
            seen += historyText
            response("之前的工作已完成。")
        }, AgentRunController()).compact(jsonHistory(), 1, emptySet(), force = true)
        assertEquals(3, requests)
        for (turn in 1..4) assertTrue(seen.any { it.contains("问题 $turn") })
        assertTrue(result.toString().contains("问题 6"))
    }

    @Test
    fun completedAnswerRemainsSuccessfulWhenFinalCompactionFails() {
        val events = mutableListOf<AgentEvent>()
        val finalText = "完整结果".repeat(17_000)
        val result = AgentModelClient.complete(config.copy(contextWindow = 80_000), "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) response("不完整", "length")
                else response(finalText)
            }, onEvent = events::add)
        assertEquals(finalText, result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertNotNull(result.contextSnapshot)
        assertEquals(finalText, result.contextSnapshot!!.messages.last().content)
        assertEquals(finalText, result.transcript.last().content)
    }

    private fun history() = (1..6).flatMap { turn -> listOf(
        AgentModelClient.ConversationMessage("user", "问题 $turn"),
        AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(1000)),
    ) }

    private fun jsonHistory() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则")).also {
        history().forEach { message -> it.put(AgentConversationCodec.toJsonObject(message)) }
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
