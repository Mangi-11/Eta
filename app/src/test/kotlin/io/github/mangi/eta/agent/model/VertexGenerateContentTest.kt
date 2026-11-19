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
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexGenerateContentTest {
    @Test
    fun urlsResolveStandardGlobalRegionalMultiregionAndCustomProxyEndpoints() {
        for ((location, hostname) in listOf(
            "global" to "aiplatform.googleapis.com",
            "asia-southeast1" to "asia-southeast1-aiplatform.googleapis.com",
            "us" to "aiplatform.us.rep.googleapis.com",
            "eu" to "aiplatform.eu.rep.googleapis.com",
        )) {
            val base = "https://$hostname/v1beta1/projects/my-proj/locations/$location/publishers/google/models/gemini-4-argon"
            assertEquals("$base:streamGenerateContent?alt=sse", ProviderUrls.vertexStreamGenerateContentUrl(
                GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL, "models/gemini-4-argon", "my-proj", location,
            ))
            assertEquals("$base:countTokens", ProviderUrls.vertexCountTokensUrl(
                GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL, "gemini-4-argon", "my-proj", location,
            ))
        }
        val scoped = "https://aiplatform.googleapis.com/v1beta1/projects/my-proj/locations/eu"
        assertEquals(
            "https://aiplatform.eu.rep.googleapis.com/v1beta1/projects/my-proj/locations/eu/publishers/google/models/gemini-4-neon:countTokens",
            ProviderUrls.vertexCountTokensUrl(scoped, "gemini-4-neon"),
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
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUrls.vertexCountTokensUrl(GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL, "gemini-4-neon")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUrls.vertexCountTokensUrl(scoped, "gemini-4-neon", "different-project", "eu")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUrls.vertexCountTokensUrl(scoped, "gemini-2.5-flash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUrls.vertexCountTokensUrl("http://proxy.example.com/v1beta1", "gemini-4-neon")
        }
    }

    @Test
    fun vertexPresetsOfferOnlyGemini4InTheConfiguredLocation() {
        val provider = GeminiProviderSetting(
            id = "vertex", name = "Vertex AI", baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
            endpointMode = GeminiEndpointMode.VERTEX_AI, vertexProject = "my-proj",
        )
        assertEquals(setOf("gemini-4-neon", "gemini-4-hydrogen", "gemini-4-carbon", "gemini-4-argon"),
            OfficialModelCatalog.modelsForProvider(provider).map { it.modelId }.toSet())
        assertEquals(listOf("gemini-4-argon"),
            OfficialModelCatalog.modelsForProvider(provider.copy(vertexLocation = "europe-west2")).map { it.modelId })
        assertTrue(OfficialModelCatalog.modelsForProvider(provider.copy(vertexLocation = "us-central1")).isEmpty())
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
        assertEquals(1, toolList.length())
        val decl = toolList.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertFalse(decl.getJSONObject("parametersJsonSchema").has("\$schema"))
        assertTrue(decl.getJSONObject("parametersJsonSchema").getJSONArray("oneOf").getJSONObject(0).has("properties"))
        assertFalse(cfg.usesHostedWebSearch)

        val defaultBody = VertexGenerateContentRequest.build(cfg.copy(reasoningEffort = ReasoningEffort.DEFAULT), messages, tools)
        val defaultThinking = defaultBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertTrue(defaultThinking.getBoolean("includeThoughts"))
        assertFalse(defaultThinking.has("thinkingLevel"))

        val compactionBody = VertexGenerateContentRequest.build(cfg, messages, tools, ProviderRequestPurpose.COMPACTION)
        assertEquals("NONE", compactionBody.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))

        for (effort in listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL, ReasoningEffort.MAX)) {
            assertThrows(IllegalArgumentException::class.java) {
                VertexGenerateContentRequest.build(cfg.copy(reasoningEffort = effort), messages, tools)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VertexGenerateContentRequest.build(cfg.copy(model = "gemini-4-carbon"), messages, tools)
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
        val callId = calls.single().id
        val nextResponse = VertexGenerateContentStream({}).read(sse.byteInputStream(), AgentRunController())
        assertNotEquals(callId, AgentConversationCodec.parseToolCalls(nextResponse).single().id)
        assertEquals("lookup", calls.single().name)

        // 构建多轮上下文：旧历史（无签名）+ 当前 run 起点 + 带签名 assistant + 工具结果
        val oldAssistant = JSONObject().put("role", "assistant").put("content", "旧回答")
        val currentUser = AgentConversationCodec.userTextMessage("查一下").also(GeminiEphemeralState::markRunStart)
        val assistantMsg = AgentConversationCodec.assistantHistoryMessage(assistant, calls)
        val toolResultMsg = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", callId)
            .put("content", """{"ok":true,"value":"结果"}""")
        val messages = JSONArray()
            .put(oldAssistant)
            .put(currentUser)
            .put(assistantMsg)
            .put(toolResultMsg)

        assertTrue(SignedToolContext.hasPendingResponse(JSONArray().put(currentUser).put(assistantMsg)))

        val image = AgentModelClient.ModelImage("data:image/png;base64,AA==", "image/png", 1)
        val contents = VertexContentHistory.contents(messages, mapOf(callId to listOf(image)))
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
        assertEquals("AA==", fnResp.getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data"))
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(AgentConversationCodec.transcript(messages, 0)).contains("AA=="))
    }

    @Test
    fun agentLoopAttachesImagesToTheirCallsForOnlyTheNextRequest() {
        val sent = mutableListOf<JSONArray>()
        val cfg = config().copy(hostedWebSearchEnabled = true, supportsSearchSuggestions = true)
        val provider = object : AgentProviderClient by VertexGenerateContentProvider {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                assertTrue((0 until request.tools.length()).any { index ->
                    request.tools.getJSONObject(index).getJSONObject("function").optString("name") == "web_search"
                })
                sent += VertexGenerateContentRequest.build(
                    request.effectiveConfig, request.messages, request.effectiveTools, request.purpose, request.toolResultImages,
                ).getJSONArray("contents")
                val chunk = when (sent.size) {
                    1 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"a","name":"capture_a"},"thoughtSignature":"sig1"},{"functionCall":{"id":"b","name":"capture_b"}}]},"finishReason":"STOP"}]}"""
                    2 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"a","name":"capture_b"},"thoughtSignature":"sig2"}]},"finishReason":"STOP"}]}"""
                    else -> """{"candidates":[{"content":{"parts":[{"text":"已完成"}]},"finishReason":"STOP"}]}"""
                }
                return ProviderResponse(VertexGenerateContentStream(onEvent).read(sseChunk(chunk).byteInputStream(), runController))
            }
        }
        val tools = JSONArray()
        for (name in listOf("capture_a", "capture_b")) {
            tools.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name)))
        }
        val result = AgentModelClient.complete(
            config = cfg,
            prompt = "测试工具截图",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                val data = if (call.name == "capture_a") "AA==" else "AQ=="
                AgentModelClient.ToolResult("{}", listOf(AgentModelClient.ModelImage("data:image/png;base64,$data", "image/png", 1)))
            },
            additionalTools = tools,
            provider = provider,
        )
        fun responseParts(request: JSONArray, turn: Int): List<JSONObject> {
            val parts = request.getJSONObject(turn).getJSONArray("parts")
            return (0 until parts.length()).map { parts.getJSONObject(it).getJSONObject("functionResponse") }
        }
        fun imageData(response: JSONObject): String = response.getJSONArray("parts").getJSONObject(0)
            .getJSONObject("inlineData").getString("data")

        val firstBatch = responseParts(sent[1], 2)
        assertEquals(listOf("a", "b"), firstBatch.map { it.getString("id") })
        assertEquals(listOf("AA==", "AQ=="), firstBatch.map(::imageData))
        assertTrue(responseParts(sent[2], 2).none { it.has("parts") })
        assertEquals("AQ==", imageData(responseParts(sent[2], 4).single()))
        assertEquals("已完成", result.content)
        val transcript = AgentConversationCodec.encodeTranscriptForStorage(result.transcript)
        assertFalse(transcript.contains("data:image"))
        assertFalse(transcript.contains("AA==") || transcript.contains("AQ=="))
    }

    @Test
    fun streamRequiresNaturalStopBeforeReturningTextOrToolCalls() {
        val call = sseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"lookup","args":{"q":"值"}},"thoughtSignature":"fixture"}]}}]}""",
        )
        val text = sseChunk("""{"candidates":[{"content":{"role":"model","parts":[{"text":"半截输出"}]}}]}""")
        for ((suffix, error) in listOf(
            "" to "STREAM_INCOMPLETE",
            "data: [DONE]\n\n" to "STREAM_INCOMPLETE",
            """data: {"candidates":[{"finishReason":"STOP"}]}""" to "STREAM_INCOMPLETE",
            sseChunk("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""") to "MODEL_OUTPUT_LIMIT",
            sseChunk("""{"candidates":[{"finishReason":"CONTINUATION","continuationToken":"fixture"}]}""") to "MODEL_OUTPUT_LIMIT",
            sseChunk("""{"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]}""") to "GEMINI_INVALID_TOOL_CALL",
            sseChunk("""{"candidates":[{"finishReason":"SAFETY"}]}""") to "CONTENT_FILTER",
        )) {
            for (prefix in listOf(call, text)) {
                val failure = assertThrows(AgentModelFailure::class.java) {
                    VertexGenerateContentStream({}).read((prefix + suffix).byteInputStream(), AgentRunController())
                }
                assertEquals(error, failure.code)
            }
        }
        assertThrows(AgentModelFailure::class.java) {
            val partial = sseChunk(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"lookup","willContinue":true},"thoughtSignature":"fixture"}]},"finishReason":"STOP"}]}""",
            )
            VertexGenerateContentStream({}).read(partial.byteInputStream(), AgentRunController())
        }
        val finished = sseChunk("""{"candidates":[{"finishReason":"STOP"}]}""")
        val first = VertexGenerateContentStream({}).read((call + finished).byteInputStream(), AgentRunController())
        val second = VertexGenerateContentStream({}).read((call + finished).byteInputStream(), AgentRunController())
        assertNotEquals(AgentConversationCodec.parseToolCalls(first).single().id, AgentConversationCodec.parseToolCalls(second).single().id)
    }

    @Test
    fun providerUsesConfiguredEndpointAndDoesNotFollowRedirectsOrGuessProjects() {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val nextStatus = AtomicInteger(404)
        server.createContext("/") { exchange ->
            val status = nextStatus.get()
            requestedPaths += exchange.requestURI.toString()
            assertEquals("AQ.fixture", exchange.requestHeaders.getFirst("x-goog-api-key"))
            if (status == 307) exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/other")
            val payload = if (status == 200) {
                sseChunk("""{"candidates":[{"content":{"parts":[{"text":"已完成"}]},"finishReason":"STOP"}]}""")
            } else {
                """{"error":{"message":"projects/987654321/locations/asia-southeast1/model missing"}}"""
            }
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        target.start()
        server.start()
        try {
            val request = ProviderRequest(
                config().copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1beta1", vertexProject = ""),
                JSONArray().put(AgentConversationCodec.userTextMessage("你好")),
                JSONArray(),
            )
            for (httpStatus in listOf(404, 307)) {
                nextStatus.set(httpStatus)
                val failure = assertThrows(AgentModelFailure::class.java) {
                    VertexGenerateContentProvider.complete(request, AgentRunController()) {}
                }
                assertEquals("HTTP_$httpStatus", failure.code)
            }
            nextStatus.set(200)
            val response = VertexGenerateContentProvider.complete(request, AgentRunController()) {}
            assertEquals("已完成", response.assistantMessage.getString("content"))
            assertEquals(List(3) { "/v1beta1/publishers/google/models/gemini-4-hydrogen:streamGenerateContent?alt=sse" }, requestedPaths)
        } finally {
            server.stop(0)
            target.stop(0)
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
