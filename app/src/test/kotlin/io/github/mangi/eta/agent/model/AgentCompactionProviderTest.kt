package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionProviderTest {
    @Test
    fun allProtocolsDisableToolsAndIgnoreCustomInputOverridesForSummaries() {
        listOf(OpenAiChatCompletionsProvider, OpenAiResponsesProvider, AnthropicMessagesProvider).forEach { provider ->
            val captured = AtomicReference<JSONObject>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
                val body = """{"error":{"code":"fixture_rejected"}}""".toByteArray()
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val config = AgentModelClient.ModelConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture",
                    model = "fixture", openAiEndpointMode = if (provider === OpenAiResponsesProvider) io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES
                        else io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS, systemPrompt = "总结规则", hostedWebSearchEnabled = true,
                    extraBodyJson = """{"tools":[{"type":"web_search"}],"input":"BAD_INPUT"}""",
                    customBody = listOf(CustomBody("messages", JsonPrimitive("BAD_MESSAGES"))),
                )
                val request = ProviderRequest(config,
                    JSONArray().put(AgentConversationCodec.userTextMessage("总结以下历史")),
                    AgentToolCatalog.build(terminalTools = false, browserTools = false), purpose = ProviderRequestPurpose.COMPACTION)
                assertThrows(AgentModelFailure::class.java) {
                    provider.complete(request, AgentRunController())
                }
                val body = captured.get()
                assertNotNull(body)
                assertFalse(body.has("tools"))
                assertFalse(body.has("tool_choice"))
                assertFalse(body.toString().contains("BAD_INPUT"))
                assertFalse(body.toString().contains("BAD_MESSAGES"))
                assertTrue(body.toString().contains("总结以下历史"))
            } finally {
                server.stop(0)
            }
        }
    }
}
