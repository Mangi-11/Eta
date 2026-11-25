package io.github.mangi.eta.agent.model.provider.gemini

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.ui.state.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.latestContextUsage
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.SignedToolContext
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderResponse

class GeminiRuntimeIntegrationTest {
    private val config = AgentModelClient.ModelConfig(
        providerType = ProviderTypes.GEMINI,
        baseUrl = "https://example.invalid/v1beta", apiKey = "fixture", model = "gemini-3.8-flash",
        systemPrompt = "固定约束", reasoningEffort = ReasoningEffort.DEFAULT,
        reasoningCapabilities = GeminiModelCapabilities.reasoningCapabilities("gemini-3.8-flash"),
    )

    @Test
    fun usageReceivedBeforeTextStillReachesTheContextMeterAfterCompletion() {
        val stream = """
            data: {"event_type":"interaction.created","interaction":{"id":"fixture","status":"in_progress","usage":{"total_tokens":125,"total_input_tokens":100,"total_output_tokens":20,"total_thought_tokens":5,"total_cached_tokens":60}}}

            data: {"event_type":"step.start","index":0,"step":{"type":"model_output","content":[{"type":"text","text":"完成"}]}}

            data: {"event_type":"step.stop","index":0}

            data: {"event_type":"interaction.completed","interaction":{"status":"completed","usage":{"total_tokens":125,"total_input_tokens":100,"total_output_tokens":20,"total_thought_tokens":5,"total_cached_tokens":60}}}

        """.trimIndent()
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = emptyList<AgentChatMessageUi>()
        AgentModelClient.complete(config, "测试", AgentModelClient.ToolExecutor { error("不应执行工具") },
            provider = provider { _, emit ->
                GeminiInteractionsStream(emit).read(ByteArrayInputStream(stream.toByteArray()), AgentRunController())
            }, onEvent = { messages = projector.applyEvent("usage-run", it, messages) })
        val model = AgentModelOptionUi("fixture", "gemini", "Gemini", "gemini",
            "gemini-3.8-flash", "Gemini", 1_000)
        val contextUsage = latestContextUsage(messages, model)
        assertEquals(125, contextUsage.contextTokens)
        assertEquals(0.125f, requireNotNull(contextUsage.progress), 0f)
        val answer = messages.filterIsInstance<AgentMessageUi>().single()
        assertFalse(answer.isStreaming)
        assertEquals(100, answer.usage?.inputTokens)
        assertEquals(60, answer.usage?.cachedTokens)
    }

    @Test
    fun toolLoopPreservesRawStepsAndPublishesOnlyDurableTranscript() {
        val requests = mutableListOf<JSONArray>()
        val transcripts = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        val executed = mutableListOf<String>()
        var round = 0
        val provider = provider { request, _ ->
            requests += GeminiInteractionsRequest.build(request.effectiveConfig, request.messages,
                request.effectiveTools).getJSONArray("input")
            if (round++ == 0) {
                signedCall()
            } else {
                JSONObject().put("role", "assistant").put("content", "已完成").put("finish_reason", "stop")
            }
        }
        val result = AgentModelClient.complete(
            config, "继续", AgentModelClient.ToolExecutor {
                executed += it.id
                AgentModelClient.ToolResult("""{"ok":false,"code":"FIXTURE_FAILURE"}""")
            },
            history = listOf(AgentModelClient.ConversationMessage("assistant", "旧回答")),
            provider = provider, onTranscript = transcripts::add,
        )
        assertEquals(listOf("call-1"), executed)
        assertTrue(requests.first().getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text").contains("历史对话文本记录"))
        val second = requests[1]
        val thought = (0 until second.length()).map(second::getJSONObject)
            .single { it.optString("type") == "thought" }
        assertEquals("opaque-fixture", thought.getString("signature"))
        val toolResult = (0 until second.length()).map(second::getJSONObject)
            .single { it.optString("type") == "function_result" }
        assertEquals("call-1", toolResult.getString("call_id"))
        assertTrue(toolResult.getBoolean("is_error"))
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(result.transcript).contains("opaque-fixture"))
        assertTrue(transcripts.isNotEmpty())
        assertTrue(transcripts.all { !AgentConversationCodec.encodeTranscriptForStorage(it).contains("opaque-fixture") })
    }

    @Test
    fun pendingSignedToolChainPreventsCompactionEvenAfterLocalResultsExist() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("执行任务"))
            .put(signedCall()).put(JSONObject().put("role", "tool")
                .put("tool_call_id", "call-1").put("content", "已执行"))
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextCompactor(config, provider { _, _ -> error("不能发送摘要请求") }, AgentRunController())
                .compact(messages, 0, emptySet())
        }
        assertEquals("GEMINI_THINKING_CONTEXT_LOCKED", failure.code)
        messages.put(JSONObject().put("role", "assistant").put("content", "任务完成"))
        assertFalse(SignedToolContext.hasPendingResponse(messages))
    }

    @Test
    fun searchReplacesLocalToolOnlyForSupportedModelAndPresentation() {
        for (model in listOf("gemini-3.8-flash", "gemini-unknown")) {
            for (presentation in listOf(false, true)) {
                val configured = config.copy(model = model, hostedWebSearchEnabled = true,
                    supportsSearchSuggestions = presentation)
                val expected = model == "gemini-3.8-flash" && presentation
                AgentModelClient.complete(configured, "查询", AgentModelClient.ToolExecutor { error("不应执行") },
                    provider = provider { request, _ ->
                        val names = (0 until request.tools.length()).map {
                            request.tools.getJSONObject(it).getJSONObject("function").getString("name")
                        }
                        assertEquals(!expected, "web_search" in names)
                        assertTrue("fetch_url" in names)
                        assertTrue("browser_use" in names)
                        assertEquals(expected, request.messages.toString().contains("托管的 google_search"))
                        JSONObject().put("content", "完成").put("finish_reason", "stop")
                    })
            }
        }
    }

    @Test
    fun searchSuggestionProviderEventKeepsRoundAndTextBlockIdentity() {
        val events = mutableListOf<AgentEvent>()
        AgentModelClient.complete(config, "查询", AgentModelClient.ToolExecutor { error("不应执行") },
            provider = provider { _, emit ->
                emit(ProviderEvent.SearchSuggestions(7, "<div>搜索建议</div>"))
                JSONObject().put("content", "完成").put("finish_reason", "stop")
            }, onEvent = events::add)
        val event = events.filterIsInstance<AgentEvent.SearchSuggestions>().single()
        assertEquals(1, event.round)
        assertEquals(7, event.index)
    }

    private fun signedCall(): JSONObject = JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))))
        .also { message ->
            GeminiEphemeralState.attachSteps(message, JSONArray()
                .put(JSONObject().put("type", "thought").put("signature", "opaque-fixture"))
                .put(JSONObject().put("type", "function_call").put("id", "call-1")
                    .put("name", "get_current_context").put("arguments", JSONObject())))
        }

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> JSONObject) =
        object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = GeminiInteractionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit) = ProviderResponse(block(request, onEvent))
        }
}
