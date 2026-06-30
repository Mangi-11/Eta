package fuck.andes.agent.model

import com.sun.net.httpserver.HttpServer
import fuck.andes.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Executors
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
            assertEquals(2, events.filterIsInstance<ProviderEvent.ToolCallDelta>().size)
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

    private fun providerRequest(baseUrl: String): ProviderRequest =
        ProviderRequest(
            config = AgentModelClient.ModelConfig(
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                terminalTools = true
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

    private fun withSseServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
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
