package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
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
        assertIsolatedRequests(ProviderRequestPurpose.COMPACTION)
    }

    @Test
    fun allProtocolsDisableToolsAndIgnoreCustomInputOverridesForReplyRewrites() {
        assertIsolatedRequests(ProviderRequestPurpose.REPLY_REWRITE)
    }

    @Test
    fun anthropicPreservesDialogueDepthAndCollectsSystemDepth() {
        val captured = AtomicReference<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
            val body = "{\"error\":{\"code\":\"fixture_rejected\"}}".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            listOf("user", "assistant", "system").forEach { role ->
                val card = CharacterCardCodec.decodeJson("""{"name":"林舟","extensions":{"depth_prompt":{
                    "prompt":"深度设定","depth":1,"role":"$role"}}}""")
                val context = RoleplayRunContext("fixture", card, "用户", "")
                val source = JSONArray().put(context.personaMessage())
                    .put(AgentConversationCodec.userTextMessage("第一句").put("_eta_message_id", "first"))
                    .put(JSONObject().put("role", "assistant").put("content", "第二句"))
                val config = AgentModelClient.ModelConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture",
                    model = "fixture", systemPrompt = "",
                )
                assertThrows(AgentModelFailure::class.java) {
                    AnthropicMessagesProvider.complete(ProviderRequest(config, context.projectMessages(source), JSONArray()), AgentRunController())
                }
                val body = captured.get()
                assertFalse(body.toString().contains("_eta_message_id"))
                if (role == "system") assertTrue(body.getString("system").contains("深度设定")) else {
                    val text = body.getJSONArray("messages").toString()
                    assertTrue(text.indexOf("第一句") < text.indexOf("深度设定"))
                    assertTrue(text.indexOf("深度设定") < text.indexOf("第二句"))
                }
            }
        } finally {
            server.stop(0)
        }
    }

    private fun assertIsolatedRequests(purpose: ProviderRequestPurpose) {
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
                    AgentToolCatalog.build(terminalTools = false, browserTools = false), purpose = purpose)
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
