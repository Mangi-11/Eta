package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiInteractionsTest {
    @Test
    fun existingCarbonModelResolvesLevelsAndRequestsSummaries() {
        val model = Model(id = "carbon", modelId = "gemini-4-carbon", displayName = "Carbon")
        val capabilities = requireNotNull(RuntimeConfigRepository.reasoningCapabilities(
            GeminiProviderSetting(id = "gemini", name = "Gemini"), model,
        ))
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH), capabilities.selectableEfforts)
        assertEquals(ReasoningEffort.MEDIUM, capabilities.defaultEffort)
        assertTrue(capabilities.mandatory)
        assertFalse(capabilities.supportsBudget)
        assertEquals(capabilities, GeminiModelCapabilities.reasoningCapabilities(" models/gemini-4-carbon "))
        for (effort in capabilities.selectableEfforts) {
            val body = GeminiInteractionsRequest.build(config().copy(model = model.modelId,
                reasoningCapabilities = capabilities, reasoningEffort = effort), JSONArray(), JSONArray())
            val generation = body.getJSONObject("generation_config")
            assertEquals("auto", generation.getString("thinking_summaries"))
            assertFalse(generation.has("thinking_budget"))
            if (effort == ReasoningEffort.DEFAULT) {
                assertFalse(generation.has("thinking_level"))
            } else {
                assertEquals(effort.wireValue, generation.getString("thinking_level"))
            }
        }
    }

    @Test
    fun thoughtSummaryStartAndDeltaReachVisibleThinkingWithoutExposingSignature() {
        val events = mutableListOf<ProviderEvent>()
        val result = read(
            start(0, """{"type":"thought","summary":[{"type":"text","text":"检查"}]}""") +
                delta(0, """{"type":"thought_summary","content":{"type":"text","text":"条件"}}""") +
                delta(0, """{"type":"thought_signature","signature":"opaque-fixture"}""") + stop(0) +
                start(1, """{"type":"model_output","content":[{"type":"text","text":"答案"}]}""") +
                stop(1) + complete("completed"), events,
        )
        assertEquals("检查条件", result.getString("reasoning_content"))
        assertEquals("答案", result.getString("content"))
        assertEquals(listOf("检查", "条件"), events.filterIsInstance<ProviderEvent.BlockDelta>()
            .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta })
        assertFalse(events.toString().contains("opaque-fixture"))
    }

    @Test
    fun fragmentedParallelCallsKeepOriginalIdsAndSignedThoughtWithoutSummary() {
        val events = mutableListOf<ProviderEvent>()
        val result = read(
            start(0, """{"type":"thought"}""") +
                delta(0, """{"type":"thought_signature","signature":"opaque-fixture"}""") + stop(0) +
                start(1, """{"type":"function_call","id":"call-a","name":"lookup","arguments":{}}""") +
                delta(1, JSONObject().put("type", "arguments_delta").put("arguments", "{\"query\":")) +
                delta(1, JSONObject().put("type", "arguments_delta").put("arguments", "\"甲\"}")) + stop(1) +
                start(2, """{"type":"function_call","id":"call-b","name":"lookup","arguments":{"query":"乙"}}""") + stop(2) +
                complete("requires_action"), events,
        )
        assertEquals("tool_calls", result.getString("finish_reason"))
        val calls = AgentConversationCodec.parseToolCalls(result)
        assertEquals(listOf("call-a", "call-b"), calls.map { it.id })
        assertEquals(listOf("lookup", "lookup"), calls.map { it.name })
        assertEquals("甲", JSONObject(calls.first().argumentsJson).getString("query"))
        assertEquals("opaque-fixture", GeminiEphemeralState.steps(result)!!.getJSONObject(0).getString("signature"))
        assertFalse(result.has("reasoning_content"))
        assertEquals(2, events.filterIsInstance<ProviderEvent.BlockStart>().count { it.kind == AssistantBlockKind.TOOL_CALL })
    }

    @Test
    fun toolFollowupReplaysRawStepsAndImageObservationWithoutPersistingSignature() {
        val response = read(start(0, """{"type":"thought","signature":"test-signature","summary":[]}""") + stop(0) +
            start(1, """{"type":"function_call","id":"call-a","name":"capture","arguments":{}}""") + stop(1) + complete("requires_action"))
        val assistant = AgentConversationCodec.assistantHistoryMessage(response, AgentConversationCodec.parseToolCalls(response))
        val user = AgentConversationCodec.userTextMessage("查看屏幕").also(GeminiEphemeralState::markRunStart)
        val messages = JSONArray().put(user).put(assistant)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call-a").put("content", "{\"ok\":false}"))
            .put(AgentConversationCodec.userMessage("工具截图", listOf(AgentModelClient.ModelImage("data:image/png;base64,AA==", "image/png", 1)))
                .put("_eta_observation", true))
        assertTrue(GeminiEphemeralState.hasPendingToolResponse(messages))
        val input = GeminiInteractionsRequest.build(config(), messages, JSONArray()).getJSONArray("input")
        assertEquals(listOf("user_input", "thought", "function_call", "function_result", "user_input"),
            (0 until input.length()).map { input.getJSONObject(it).getString("type") })
        assertEquals("test-signature", input.getJSONObject(1).getString("signature"))
        assertEquals("call-a", input.getJSONObject(3).getString("call_id"))
        assertTrue(input.getJSONObject(3).getBoolean("is_error"))
        val image = input.getJSONObject(4).getJSONArray("content").getJSONObject(1)
        assertEquals("image/png", image.getString("mime_type"))
        assertEquals("AA==", image.getString("data"))
        val durable = AgentConversationCodec.encodeTranscriptForStorage(listOf(AgentConversationCodec.durableMessage(assistant)))
        assertFalse(durable.contains("test-signature"))
        assertFalse(GeminiEphemeralState.withoutState(assistant).toString().contains("test-signature"))
    }

    @Test
    fun durableAndCompactedToolsAreExplicitTextHistory() {
        val oldAssistant = JSONObject("""{"role":"assistant","content":"旧答复","tool_calls":[{"id":"old","type":"function","function":{"name":"lookup","arguments":"{}"}}]}""")
        val oldResult = JSONObject("""{"role":"tool","tool_call_id":"old","content":"旧结果"}""")
        val current = AgentConversationCodec.userTextMessage("新问题").also(GeminiEphemeralState::markRunStart)
        val input = GeminiInteractionHistory.input(JSONArray().put(AgentConversationCodec.userTextMessage("旧问题"))
            .put(oldAssistant).put(oldResult).put(current))
        assertEquals(2, input.length())
        assertTrue(input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").contains("历史对话文本记录"))
        assertEquals("新问题", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse((0 until input.length()).any { input.getJSONObject(it).getString("type") == "function_call" })

        val compacted = GeminiInteractionHistory.input(JSONArray().put(current).put(oldAssistant).put(oldResult))
        assertEquals("user_input", compacted.getJSONObject(1).getString("type"))
        assertTrue(compacted.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").contains("旧结果"))
    }

    @Test
    fun terminalWithoutStepsUsesAccumulatedTextAndCumulativeUsageIsNotSummed() {
        val events = mutableListOf<ProviderEvent>()
        val usage = JSONObject().put("total_tokens", 16).put("total_input_tokens", 10)
            .put("total_output_tokens", 4).put("total_thought_tokens", 2).put("total_cached_tokens", 6)
        val result = read(start(0, """{"type":"model_output"}""") +
            delta(0, """{"type":"text","text":"你好"}""") +
            event(JSONObject().put("event_type", "step.stop").put("index", 0).put("usage", usage)) +
            event(JSONObject().put("event_type", "interaction.status_update").put("status", "in_progress").put("usage", usage)) +
            complete("completed", usage), events)
        assertEquals("你好", result.getString("content"))
        assertEquals(1, events.filterIsInstance<ProviderEvent.Usage>().size)
        assertEquals(4, events.filterIsInstance<ProviderEvent.Usage>().single().usage.outputTokens)
        assertEquals(16, events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens)
        assertEquals(10, events.filterIsInstance<ProviderEvent.Usage>().single().contextInputTokens)
    }

    @Test
    fun partialUsageSnapshotsMergeAndPublishAfterTheTextBlock() {
        val events = mutableListOf<ProviderEvent>()
        read(event(JSONObject().put("event_type", "interaction.created").put("interaction", JSONObject()
            .put("usage", JSONObject().put("total_tokens", 125).put("total_input_tokens", 100)))) +
            start(0, """{"type":"model_output"}""") +
            event(JSONObject().put("event_type", "step.delta").put("index", 0)
                .put("delta", JSONObject().put("type", "text").put("text", "完成"))
                .put("metadata", JSONObject().put("total_usage", JSONObject().put("total_cached_tokens", 60)))) +
            event(JSONObject().put("event_type", "step.stop").put("index", 0)
                .put("usage", JSONObject().put("total_output_tokens", 20))) +
            complete("completed", JSONObject().put("total_thought_tokens", 5)), events)
        val received = events.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(125, received.usage.contextTokens)
        assertEquals(100, received.contextInputTokens)
        assertEquals(20, received.usage.outputTokens)
        assertEquals(5, received.usage.reasoningTokens)
        assertEquals(60, received.usage.cachedTokens)
        assertTrue(events.indexOf(received) > events.indexOfLast { it is ProviderEvent.BlockEnd })
    }

    @Test
    fun totalOnlyUsageDoesNotInventInputTokensAndToolTurnsStillPublishUsage() {
        val totalOnly = mutableListOf<ProviderEvent>()
        read(start(0, """{"type":"model_output","content":[{"type":"text","text":"完成"}]}""") +
            stop(0) + complete("completed", JSONObject().put("total_tokens", 125)), totalOnly)
        val total = totalOnly.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(125, total.usage.contextTokens)
        assertEquals(null, total.contextInputTokens)

        val toolEvents = mutableListOf<ProviderEvent>()
        read(start(0, """{"type":"function_call","id":"fixture","name":"lookup","arguments":{}}""") +
            stop(0) + complete("requires_action", JSONObject().put("total_tokens", 125)
                .put("total_input_tokens", 100)), toolEvents)
        val toolUsage = toolEvents.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(125, toolUsage.usage.contextTokens)
        assertEquals(100, toolUsage.contextInputTokens)
    }

    @Test
    fun incompleteTextIsOutputLimitAndIncompleteCallsNeverExecute() {
        val events = mutableListOf<ProviderEvent>()
        val partial = start(0, """{"type":"model_output","content":[{"type":"text","text":"半段"}]}""") + stop(0)
        val failure = assertFailure("MODEL_OUTPUT_LIMIT", partial + complete("incomplete"), events)
        assertFalse(failure.retryable)
        assertEquals("半段", events.filterIsInstance<ProviderEvent.BlockEnd>().single().content)
        assertFalse(events.any { it is ProviderEvent.Completed })
        val call = start(0, """{"type":"function_call","id":"a","name":"lookup","arguments":{}}""")
        assertFalse(assertFailure("MODEL_OUTPUT_LIMIT", call + stop(0) + complete("incomplete")).retryable)
        assertFalse(assertFailure("MODEL_OUTPUT_LIMIT", call + complete("incomplete")).retryable)
        assertFalse(assertFailure("MODEL_OUTPUT_LIMIT", partial + complete("budget_exceeded")).retryable)
        assertFailure("STREAM_INCOMPLETE", start(0, """{"type":"function_call","id":"a","name":"lookup","arguments":{}}""") +
            stop(0) + complete("completed"))
    }

    @Test
    fun incompleteSearchAnswerKeepsReceivedSuggestionsWithItsPartialText() {
        val events = mutableListOf<ProviderEvent>()
        val failure = assertFailure("MODEL_OUTPUT_LIMIT", searchSteps() + start(2, """{"type":"model_output"}""") +
            delta(2, """{"type":"text","text":"未完整的搜索答复"}""") + complete("incomplete"), events)
        assertFalse(failure.retryable)
        val suggestion = events.filterIsInstance<ProviderEvent.SearchSuggestions>().single()
        assertEquals(2, suggestion.index)
        assertTrue(events.indexOf(suggestion) > events.indexOfLast { it is ProviderEvent.BlockDelta })
    }

    @Test
    fun unknownEventsAreIgnoredButUnsupportedOutputAndMalformedEventsFail() {
        assertEquals("好", read(event(JSONObject().put("event_type", "future.event")) + textResponse("好")).getString("content"))
        assertFailure("GEMINI_UNSUPPORTED_OUTPUT", start(0, """{"type":"model_output"}""") + delta(0, """{"type":"audio","data":"redacted"}"""))
        assertFailure("GEMINI_STREAM_INVALID", "data: {broken fixture}\n\n")
        assertFailure("GEMINI_STREAM_INVALID", delta(0, """{"type":"text","text":"孤立"}"""))
        assertFailure("GEMINI_STREAM_INVALID", start(0, """{"type":"thought","signature":7}"""))
        assertFailure("GEMINI_STREAM_INVALID", start(0, """{"type":"function_call","id":"a","name":"lookup","arguments":{}}""") +
            delta(0, JSONObject().put("type", "arguments_delta").put("arguments", "{\"query\":")) + stop(0))
        assertFailure("GEMINI_STREAM_INVALID", complete("requires_action"))
    }

    @Test
    fun prematureEofDoneMarkerAndUnstoppedStepsFailClosed() {
        assertFailure("STREAM_INCOMPLETE", "data: [DONE]\n\n")
        assertFailure("STREAM_INCOMPLETE", start(0, """{"type":"model_output"}""") + delta(0, """{"type":"text","text":"部分"}"""))
        assertFailure("STREAM_INCOMPLETE", start(0, """{"type":"model_output"}""") + complete("completed"))
    }

    @Test
    fun thoughtSignatureRejectsNonThoughtSteps() {
        val steps = listOf(
            """{"type":"model_output"}""",
            """{"type":"function_call","id":"call-a","name":"lookup","arguments":{}}""",
            """{"type":"google_search_call","id":"search-a"}""",
        )
        for (step in steps) {
            assertFailure("GEMINI_STREAM_INVALID", start(0, step) +
                delta(0, """{"type":"thought_signature","signature":"opaque-fixture"}"""))
        }
    }

    @Test
    fun streamFailureNeverExposesProviderBody() {
        val failure = assertFailure("PROVIDER_STREAM_ERROR", event(JSONObject().put("event_type", "error")
            .put("error", JSONObject().put("code", "503").put("message", "private-fixture-prompt"))))
        assertTrue(failure.retryable)
        assertFalse(failure.toString().contains("private-fixture-prompt"))
        assertFailure("PROVIDER_STREAM_ERROR", complete("failed"))
        assertFailure("PROVIDER_STREAM_ERROR", complete("cancelled"))
    }

    @Test
    fun utf8CitationOffsetsKeepChineseAndEmojiBoundaries() {
        val text = "中😀文"
        assertEquals(text, GeminiCitationFormatter.apply(text, JSONArray()))
        val annotation = JSONObject().put("type", "url_citation").put("url", "https://example.test/source")
            .put("title", "来源").put("start_index", 0).put("end_index", 7)
        val formatted = GeminiCitationFormatter.apply(text, JSONArray().put(annotation))
        assertEquals("中😀 [[1]](<https://example.test/source>)文", formatted)
        annotation.put("end_index", 5)
        val fallback = GeminiCitationFormatter.apply(text, JSONArray().put(annotation))
        assertTrue(fallback.startsWith("中😀文\n\n来源："))
        annotation.put("end_index", 4_294_967_296L)
        assertTrue(GeminiCitationFormatter.apply(text, JSONArray().put(annotation)).startsWith("中😀文\n\n来源："))
    }

    @Test
    fun searchSignaturesAndSuggestionsAreBoundToFollowingTextAfterBlockEnd() {
        val events = mutableListOf<ProviderEvent>()
        val response = read(searchSteps() + start(2, """{"type":"model_output"}""") +
            delta(2, """{"type":"text","text":"查询结果"}""") + stop(2) + complete("completed"), events)
        val suggestion = events.filterIsInstance<ProviderEvent.SearchSuggestions>().single()
        assertEquals(2, suggestion.index)
        assertEquals("<div>建议</div>", suggestion.html)
        assertTrue(events.indexOf(suggestion) > events.indexOfLast { it is ProviderEvent.BlockEnd })
        assertEquals(1, events.filterIsInstance<ProviderEvent.HostedToolStarted>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.HostedToolFinished>().single().success)
        assertEquals("result-signature", GeminiEphemeralState.steps(response)!!.getJSONObject(1).getString("signature"))
        val durable = AgentConversationCodec.encodeTranscriptForStorage(listOf(AgentConversationCodec.durableMessage(response)))
        assertFalse(durable.contains("<div>"))
        assertFalse(durable.contains("result-signature"))
    }

    @Test
    fun searchThenLocalToolDefersSuggestionsUntilNextAnswer() {
        val firstEvents = mutableListOf<ProviderEvent>()
        val first = read(searchSteps() + start(2, """{"type":"function_call","id":"local","name":"lookup","arguments":{}}""") +
            stop(2) + complete("requires_action"), firstEvents)
        assertTrue(firstEvents.none { it is ProviderEvent.SearchSuggestions })
        assertEquals(1, GeminiEphemeralState.pendingSuggestions(first).length())
        val copied = JSONObject()
        GeminiEphemeralState.copyAssistantState(first, copied)
        val secondEvents = mutableListOf<ProviderEvent>()
        val second = GeminiInteractionsStream(secondEvents::add, GeminiEphemeralState.pendingSuggestions(copied))
            .read(ByteArrayInputStream(textResponse("最终答案").toByteArray()), AgentRunController())
        assertEquals(0, secondEvents.filterIsInstance<ProviderEvent.SearchSuggestions>().single().index)
        assertEquals(0, GeminiEphemeralState.pendingSuggestions(second).length())
    }

    @Test
    fun oversizedSuggestionsFailInsteadOfDisplayingTruncatedHtml() {
        val html = "中".repeat(22_000)
        assertFailure("GEMINI_SEARCH_SUGGESTIONS_LIMIT", searchSteps(html))
    }

    @Test
    fun bodyProtectsConversationAuthFlowAndToolChoiceButKeepsSchema() {
        val cfg = config().copy(hostedWebSearchEnabled = true, supportsSearchSuggestions = true,
            extraBodyJson = """{"store":true,"previous_interaction_id":"forbidden","generation_config":{"thinking_level":"high","max_output_tokens":120}}""",
            customBody = listOf(CustomBody("stream", JsonPrimitive(false)), CustomBody("model", JsonPrimitive("wrong"))))
        val tools = JSONArray("""[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","oneOf":[{"required":["query"]}],"properties":{"query":{"type":"string","minLength":2}}}}}]""")
        val request = GeminiInteractionsRequest.build(cfg, JSONArray().put(AgentConversationCodec.userTextMessage("问题")), tools)
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.getBoolean("store"))
        assertFalse(request.has("previous_interaction_id"))
        assertEquals("gemini-3.8-flash", request.getString("model"))
        val generation = request.getJSONObject("generation_config")
        assertEquals("validated", generation.getString("tool_choice"))
        assertEquals(120, generation.getInt("max_output_tokens"))
        assertFalse(generation.has("thinking_level"))
        assertTrue(request.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters").has("oneOf"))
        assertEquals("google_search", request.getJSONArray("tools").getJSONObject(1).getString("type"))
    }

    @Test
    fun thinkingValidatesProtocolLevelsInsteadOfModelWhitelist() {
        val capabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), mandatory = true)
        val cfg = config().copy(reasoningEffort = ReasoningEffort.LOW, reasoningCapabilities = capabilities)
        val body = GeminiInteractionsRequest.build(cfg, JSONArray(), JSONArray())
        assertEquals("low", body.getJSONObject("generation_config").getString("thinking_level"))
        val unlisted = GeminiInteractionsRequest.build(cfg.copy(model = "gemini-future",
            reasoningEffort = ReasoningEffort.MINIMAL), JSONArray(), JSONArray())
        assertEquals("minimal", unlisted.getJSONObject("generation_config").getString("thinking_level"))
        for (effort in listOf(ReasoningEffort.OFF, ReasoningEffort.XHIGH, ReasoningEffort.MAX)) {
            try {
                GeminiInteractionsRequest.build(cfg.copy(reasoningEffort = effort), JSONArray(), JSONArray())
                fail("不支持的档位必须拒绝")
            } catch (_: IllegalArgumentException) {
                // 配置错误在发出 HTTP 请求前报告。
            }
        }
    }

    @Test
    fun cancellationClosesStreamingReader() {
        val controller = AgentRunController()
        var closed = false
        val stream = object : ByteArrayInputStream(textResponse("回答").toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        try {
            GeminiInteractionsStream(onEvent = { controller.cancel() }).read(stream, controller)
            fail("取消必须传播")
        } catch (_: AgentRunCancelledException) {
            assertTrue(closed)
        }
    }

    @Test
    fun httpUsesApiKeyHeaderAndProtectedBodyWithNativeEndpoint() {
        val requestBody = AtomicReference<String>()
        val apiKey = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val accept = AtomicReference<String>()
        val contentType = AtomicReference<String>()
        withServer(200, textResponse("完成"), { body, headers ->
            requestBody.set(body)
            apiKey.set(headers.getFirst("x-goog-api-key"))
            authorization.set(headers.getFirst("Authorization"))
            accept.set(headers.getFirst("Accept"))
            contentType.set(headers.getFirst("Content-Type"))
        }) { base ->
            val result = GeminiInteractionsProvider.complete(
                ProviderRequest(config().copy(baseUrl = base,
                    customHeaders = listOf(CustomHeader("x-goog-api-key", "wrong-fixture"),
                        CustomHeader("Accept", "application/json"), CustomHeader("Content-Type", "text/plain"))),
                    JSONArray().put(AgentConversationCodec.userTextMessage("问题")), JSONArray()),
                AgentRunController(),
            )
            assertEquals("完成", result.assistantMessage.getString("content"))
        }
        assertEquals("test-key", apiKey.get())
        assertEquals(null, authorization.get())
        assertEquals("text/event-stream", accept.get())
        assertEquals("application/json; charset=utf-8", contentType.get())
        assertFalse(JSONObject(requestBody.get()).getBoolean("store"))
        assertTrue(JSONObject(requestBody.get()).getBoolean("stream"))
    }

    @Test
    fun httpErrorReportsClassificationWithoutResponseBody() {
        withServer(401, """{"error":{"status":"UNAUTHENTICATED","message":"private-error-fixture"}}""") { base ->
            try {
                GeminiInteractionsProvider.complete(ProviderRequest(config().copy(baseUrl = base), JSONArray(), JSONArray()), AgentRunController())
                fail("认证失败必须报告")
            } catch (failure: AgentModelFailure) {
                assertEquals("PROVIDER_AUTH_FAILED", failure.code)
                assertFalse(failure.retryable)
                assertFalse(failure.toString().contains("private-error-fixture"))
            }
        }
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.test/v1beta", apiKey = "test-key", model = "gemini-3.8-flash",
        providerType = ProviderTypes.GEMINI, systemPrompt = "", reasoningEffort = ReasoningEffort.DEFAULT,
    )

    private fun read(stream: String, events: MutableList<ProviderEvent> = mutableListOf()): JSONObject =
        GeminiInteractionsStream(events::add).read(ByteArrayInputStream(stream.toByteArray()), AgentRunController())

    private fun assertFailure(code: String, stream: String, events: MutableList<ProviderEvent> = mutableListOf()): AgentModelFailure {
        try {
            read(stream, events)
            fail("预期失败：$code")
        } catch (failure: AgentModelFailure) {
            assertEquals(code, failure.code)
            return failure
        }
        error("不可达")
    }

    private fun searchSteps(html: String = "<div>建议</div>"): String =
        start(0, """{"type":"google_search_call","id":"search-a","signature":""}""") +
            delta(0, """{"type":"google_search_call","arguments":{"queries":["问题"]},"signature":"call-signature"}""") + stop(0) +
            start(1, """{"type":"google_search_result","call_id":"search-a"}""") +
            delta(1, JSONObject().put("type", "google_search_result").put("signature", "result-signature")
                .put("result", JSONArray().put(JSONObject().put("search_suggestions", html)))) + stop(1)

    private fun textResponse(text: String) = start(0, JSONObject().put("type", "model_output")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).toString()) + stop(0) + complete("completed")

    private fun start(index: Int, step: String): String = event(JSONObject().put("event_type", "step.start").put("index", index).put("step", JSONObject(step)))
    private fun delta(index: Int, data: String): String = delta(index, JSONObject(data))
    private fun delta(index: Int, data: JSONObject): String = event(JSONObject().put("event_type", "step.delta").put("index", index).put("delta", data))
    private fun stop(index: Int): String = event(JSONObject().put("event_type", "step.stop").put("index", index))
    private fun complete(status: String, usage: JSONObject? = null): String = event(JSONObject().put("event_type", "interaction.completed")
        .put("interaction", JSONObject().put("status", status).apply { if (usage != null) put("usage", usage) }))
    private fun event(value: JSONObject): String = "event: ${value.getString("event_type")}\ndata: $value\n\n"

    private fun withServer(
        status: Int,
        response: String,
        onRequest: (String, com.sun.net.httpserver.Headers) -> Unit = { _, _ -> },
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1beta/interactions") { exchange ->
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }, exchange.requestHeaders)
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1beta")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
