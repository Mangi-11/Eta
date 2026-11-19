package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VertexGenerateContentTest {
    @Test
    fun urlsResolveExpressGlobalRegionalAndCustomProxyEndpoints() {
        assertEquals(
            "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/gemini-4-neon:streamGenerateContent?alt=sse",
            ProviderUrls.vertexStreamGenerateContentUrl(
                baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
                model = "models/gemini-4-neon",
            ),
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1beta1/projects/my-proj/locations/global/publishers/google/models/gemini-4-carbon:streamGenerateContent?alt=sse",
            ProviderUrls.vertexStreamGenerateContentUrl(
                baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
                model = "gemini-4-carbon",
                project = "my-proj",
                location = "global",
            ),
        )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1beta1/projects/my-proj/locations/us-central1/publishers/google/models/gemini-4-hydrogen:streamGenerateContent?alt=sse",
            ProviderUrls.vertexStreamGenerateContentUrl(
                baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
                model = "gemini-4-hydrogen",
                project = "my-proj",
                location = "us-central1",
            ),
        )
        assertEquals(
            "https://proxy.example.com/vertex/v1beta1/projects/my-proj/locations/us-central1/publishers/google/models/gemini-4-neon:streamGenerateContent?alt=sse",
            ProviderUrls.vertexStreamGenerateContentUrl(
                baseUrl = "https://proxy.example.com/vertex/v1beta1/",
                model = "gemini-4-neon",
                project = "my-proj",
                location = "us-central1",
            ),
        )
        assertEquals(
            "123456789012",
            ProviderUrls.extractVertexGlobalFallbackProject(
                """{"error":{"code":404,"message":"Publisher Model `projects/123456789012/locations/asia-southeast1/publishers/google/models/gemini-4-carbon` was not found"}}""",
            ),
        )
        assertNull(
            ProviderUrls.extractVertexGlobalFallbackProject(
                """{"error":{"code":404,"message":"Publisher Model `projects/123456789012/locations/global/publishers/google/models/unknown` was not found"}}""",
            ),
        )
    }

    @Test
    fun requestProjectsSystemToolsThinkingAndRejectsUnsupportedEfforts() {
        val capabilities = requireNotNull(GeminiModelCapabilities.reasoningCapabilities("gemini-4-neon"))
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "lookup")
                    .put("description", "查询")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                            .put("type", "object")
                            .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))
                            .put("oneOf", JSONArray().put(JSONObject().put("required", JSONArray().put("q")))),
                    ),
            ),
        )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统提示"))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "看图"))
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/png;base64,AAAA")),
                        ),
                ).also(GeminiEphemeralState::markRunStart),
            )
        val cfg = config().copy(
            model = "gemini-4-neon",
            reasoningCapabilities = capabilities,
            reasoningEffort = ReasoningEffort.XHIGH,
            hostedWebSearchEnabled = true,
            supportsSearchSuggestions = true,
            customBody = listOf(
                CustomBody(
                    "generationConfig",
                    JsonObject(
                        mapOf(
                            "maxOutputTokens" to JsonPrimitive(8192),
                            "thinkingConfig" to JsonObject(mapOf("thinkingLevel" to JsonPrimitive("LOW"))),
                        ),
                    ),
                ),
            ),
        )
        val body = VertexGenerateContentRequest.build(cfg, messages, tools)
        assertEquals("系统提示", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        val userParts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals("看图", userParts.getJSONObject(0).getString("text"))
        assertEquals("image/png", userParts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))

        val gen = body.getJSONObject("generationConfig")
        assertEquals(8192, gen.getInt("maxOutputTokens"))
        val thinking = gen.getJSONObject("thinkingConfig")
        assertTrue(thinking.getBoolean("includeThoughts"))
        assertEquals("XHIGH", thinking.getString("thinkingLevel"))

        val toolList = body.getJSONArray("tools")
        assertEquals(2, toolList.length())
        val decl = toolList.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertFalse(decl.getJSONObject("parametersJsonSchema").has("\$schema"))
        assertTrue(decl.getJSONObject("parametersJsonSchema").getJSONArray("oneOf").getJSONObject(0).has("properties"))
        assertTrue(toolList.getJSONObject(1).has("googleSearch"))

        val defaultBody = VertexGenerateContentRequest.build(cfg.copy(reasoningEffort = ReasoningEffort.DEFAULT), messages, tools)
        val defaultThinking = defaultBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertTrue(defaultThinking.getBoolean("includeThoughts"))
        assertFalse(defaultThinking.has("thinkingLevel"))

        val compactionBody = VertexGenerateContentRequest.build(cfg, messages, tools, ProviderRequestPurpose.COMPACTION)
        assertEquals("NONE", compactionBody.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))

        for (effort in listOf(ReasoningEffort.OFF, ReasoningEffort.MAX)) {
            try {
                VertexGenerateContentRequest.build(cfg.copy(reasoningEffort = effort), messages, tools)
                fail("不支持的档位必须拒绝: $effort")
            } catch (_: IllegalArgumentException) {
                // 预期拒绝。
            }
        }
    }

    @Test
    fun streamPreservesThoughtSignaturesAndReplaysToolLoopWithoutForgingOldHistory() {
        val events = mutableListOf<ProviderEvent>()
        val sse = sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"先分析"},{"thought":true,"text":"再调用"}]}}]}""",
        ) + sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"lookup","args":{"q":"甲"}},"thoughtSignature":"sig-call-1"}]}}]}""",
        ) + sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":""}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":30,"cachedContentTokenCount":10,"totalTokenCount":150}}""",
        )
        val assistant = VertexGenerateContentStream(events::add)
            .read(ByteArrayInputStream(sse.toByteArray()), AgentRunController())

        assertEquals("tool_calls", assistant.getString("finish_reason"))
        assertEquals("", assistant.getString("content"))
        assertEquals("先分析再调用", assistant.getString("reasoning_content"))
        assertEquals(0, events.filterIsInstance<ProviderEvent.BlockStart>().count { it.kind == AssistantBlockKind.TEXT })
        val usageEvent = events.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(AgentTokenUsage(contextTokens = 150, inputTokens = 100, outputTokens = 50, reasoningTokens = 30, cachedTokens = 10), usageEvent.usage)

        val calls = AgentConversationCodec.parseToolCalls(assistant)
        assertEquals(1, calls.size)
        assertEquals("call-1", calls.single().id)
        assertEquals("lookup", calls.single().name)

        // 构建多轮上下文：旧历史（无签名）+ 当前 run 起点 + 带签名 assistant + 工具结果
        val oldAssistant = JSONObject().put("role", "assistant").put("content", "旧回答")
        val currentUser = AgentConversationCodec.userTextMessage("查一下").also(GeminiEphemeralState::markRunStart)
        val assistantMsg = AgentConversationCodec.assistantHistoryMessage(assistant, calls)
        val toolResultMsg = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", "call-1")
            .put("content", """{"ok":true,"value":"结果"}""")
        val messages = JSONArray()
            .put(oldAssistant)
            .put(currentUser)
            .put(assistantMsg)
            .put(toolResultMsg)

        assertTrue(SignedToolContext.hasPendingResponse(JSONArray().put(currentUser).put(assistantMsg)))

        val contents = VertexContentHistory.contents(messages)
        // oldAssistant (降级为文本 user) 与 currentUser 合并为同一个 user turn，随后 model，随后 user(functionResponse)
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertTrue(contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").contains("历史对话文本记录"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        val modelParts = contents.getJSONObject(1).getJSONArray("parts")
        val callPart = (0 until modelParts.length()).map(modelParts::getJSONObject).first { it.has("functionCall") }
        assertEquals("sig-call-1", callPart.getString("thoughtSignature"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        val fnResp = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("call-1", fnResp.getString("id"))
        assertEquals("lookup", fnResp.getString("name"))
        assertEquals("结果", fnResp.getJSONObject("response").getString("value"))
    }

    @Test
    fun streamAppliesGroundingCitationsAndSearchSuggestionsAndRejectsMaxTokens() {
        val events = mutableListOf<ProviderEvent>()
        val sse = sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Alpha beta"}]},"finishReason":"STOP","groundingMetadata":{"webSearchQueries":["alpha"],"groundingChunks":[{"web":{"uri":"https://example.com/a","title":"Example A"}}],"groundingSupports":[{"segment":{"startIndex":0,"endIndex":5},"groundingChunkIndices":[0]}],"searchEntryPoint":{"renderedContent":"<div>Search</div>"}}}]}""",
        )
        val result = VertexGenerateContentStream(events::add)
            .read(ByteArrayInputStream(sse.toByteArray()), AgentRunController())
        assertEquals("stop", result.getString("finish_reason"))
        assertTrue(result.getString("content").contains("https://example.com/a"))
        assertTrue(events.any { it is ProviderEvent.HostedToolStarted && it.name == "web_search" })
        assertTrue(events.any { it is ProviderEvent.HostedToolFinished && it.name == "web_search" && it.success })
        assertEquals("<div>Search</div>", events.filterIsInstance<ProviderEvent.SearchSuggestions>().single().html)

        val maxTokensSse = sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"半截输出"}]},"finishReason":"MAX_TOKENS"}]}""",
        )
        try {
            VertexGenerateContentStream({}).read(ByteArrayInputStream(maxTokensSse.toByteArray()), AgentRunController())
            fail("MAX_TOKENS 必须抛出 MODEL_OUTPUT_LIMIT")
        } catch (failure: AgentModelFailure) {
            assertEquals("MODEL_OUTPUT_LIMIT", failure.code)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun providerRetriesOnceWithExtractedGlobalProjectOnRegional404() {
        val requestedPaths = mutableListOf<String>()
        val apiKeys = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" }.orEmpty())
            apiKeys += exchange.requestHeaders.getFirst("x-goog-api-key")
            if (requestedPaths.size == 1) {
                val body = """{"error":{"code":404,"message":"Publisher Model `projects/987654321/locations/asia-southeast1/publishers/google/models/gemini-4-carbon` was not found"}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                val sse = sseChunk(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"来自 global"}]},"finishReason":"STOP"}]}""",
                ).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, sse.size.toLong())
                exchange.responseBody.use { it.write(sse) }
            }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1beta1"
            val cfg = config().copy(
                baseUrl = baseUrl,
                apiKey = "AQ.test-key",
                model = "gemini-4-carbon",
                vertexProject = "",
                vertexLocation = "global",
            )
            val messages = JSONArray().put(
                AgentConversationCodec.userTextMessage("你好").also(GeminiEphemeralState::markRunStart),
            )
            val response = VertexGenerateContentProvider.complete(
                ProviderRequest(cfg, messages, JSONArray()),
                AgentRunController(),
            )
            assertEquals("来自 global", response.assistantMessage.getString("content"))
            assertEquals(
                listOf(
                    "/v1beta1/publishers/google/models/gemini-4-carbon:streamGenerateContent?alt=sse",
                    "/v1beta1/projects/987654321/locations/global/publishers/google/models/gemini-4-carbon:streamGenerateContent?alt=sse",
                ),
                requestedPaths,
            )
            assertEquals(listOf("AQ.test-key", "AQ.test-key"), apiKeys)
        } finally {
            server.stop(0)
        }
    }

    private fun sseChunk(json: String): String = "data: $json\n\n"

    private fun config(): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        providerId = "vertex-test",
        providerName = "Vertex AI",
        providerType = ProviderTypes.GEMINI,
        geminiEndpointMode = GeminiEndpointMode.VERTEX_AI,
        baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
        apiKey = "AQ.fixture",
        model = "gemini-4-hydrogen",
        systemPrompt = "",
        customHeaders = listOf(CustomHeader("x-test", "1")),
        thinkingEnabled = true,
        reasoningEffort = ReasoningEffort.DEFAULT,
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH),
            defaultEffort = ReasoningEffort.HIGH,
            defaultEnabled = true,
            mandatory = true,
        ),
    )
}
