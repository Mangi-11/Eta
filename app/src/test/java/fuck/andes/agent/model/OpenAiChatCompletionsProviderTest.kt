package fuck.andes.agent.model

import com.sun.net.httpserver.HttpServer
import fuck.andes.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProviderTest {

    @Test
    fun completeParsesTextDeltasAndRequiresDone() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Hel")))
            append(sseChunk(JSONObject().put("content", "lo"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.delta }
            )
        }
    }

    @Test
    fun completeAccumulatesChunkedToolCalls() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "需要调用工具。")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "term")
                                        .put("arguments", "{\"a\"")
                                )
                        )
                    )
                )
            )
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "inal")
                                        .put("arguments", ":1}")
                                )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            val toolCall = response.assistantMessage
                .getJSONArray("tool_calls")
                .getJSONObject(0)
            assertEquals("call_1", toolCall.getString("id"))
            assertEquals("terminal", toolCall.getJSONObject("function").getString("name"))
            assertEquals("{\"a\":1}", toolCall.getJSONObject("function").getString("arguments"))
            assertEquals("需要调用工具。", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "需要调用工具。",
                events.filterIsInstance<ProviderEvent.ReasoningDelta>().joinToString("") { it.delta }
            )
            assertEquals(2, events.filterIsInstance<ProviderEvent.ToolCallDelta>().size)
        }
    }

    @Test
    fun completeParsesReasoningUsageAndMergesExtraBody() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 8)
            .put("total_tokens", 18)
            .put(
                "completion_tokens_details",
                JSONObject().put("reasoning_tokens", 5)
            )
            .put(
                "prompt_tokens_details",
                JSONObject().put("cached_tokens", 3)
            )
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "先分析")))
            append(sseChunk(JSONObject().put("content", "结果"), finishReason = "stop"))
            append(usageChunk(usage))
            append("data: [DONE]\n\n")
        }

        val requestBody = AtomicReference<String>()
        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(
                    baseUrl = baseUrl,
                    configTransform = {
                        it.copy(
                            thinkingEnabled = true,
                            extraBodyJson = """{"enable_thinking":false,"thinking_budget":50}"""
                        )
                    }
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("结果", response.assistantMessage.getString("content"))
            assertEquals("先分析", response.assistantMessage.getString("reasoning_content"))
            val parsedUsage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, parsedUsage.contextTokens)
            assertEquals(10, parsedUsage.inputTokens)
            assertEquals(8, parsedUsage.outputTokens)
            assertEquals(5, parsedUsage.reasoningTokens)
            assertEquals(3, parsedUsage.cachedTokens)

            val request = JSONObject(requestBody.get())
            assertEquals(false, request.getBoolean("enable_thinking"))
            assertEquals(50, request.getInt("thinking_budget"))
            assertTrue(
                request.getJSONObject("stream_options").getBoolean("include_usage")
            )
        }
    }

    @Test
    fun completeRejectsStreamThatEndsBeforeDone() {
        val body = sseChunk(JSONObject().put("content", "partial"))

        withSseServer(body) { baseUrl ->
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest(baseUrl),
                    runController = AgentRunController()
                )
            }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is IllegalStateException)
            assertTrue(thrown?.message.orEmpty().contains("未正常结束"))
        }
    }

    private fun providerRequest(
        baseUrl: String,
        configTransform: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it }
    ): ProviderRequest =
        ProviderRequest(
            config = configTransform(
                AgentModelClient.ModelConfig(
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                    terminalTools = true
                )
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools = JSONArray()
        )

    private fun sseChunk(delta: JSONObject, finishReason: String? = null): String {
        val choice = JSONObject()
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }

    private fun usageChunk(usage: JSONObject): String =
        "data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n"

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            onRequest(exchange.requestBody.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
