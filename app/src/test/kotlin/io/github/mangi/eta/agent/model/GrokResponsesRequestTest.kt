package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokResponsesRequestTest {
    @Test
    fun savedChatConnectionBuildsStatelessResponsesWithMatchingModelRouting() {
        val original = config().copy(
            extraBodyJson = """{
              "model":"overridden-fixture","stream":false,"store":true,
              "previous_response_id":"previous-fixture","input":"overridden-input",
              "metadata":{"source":"fixture"},
              "include":["web_search_call.action.sources","reasoning.encrypted_content"]
            }""",
            customBody = listOf(CustomBody("model", JsonPrimitive("custom-fixture"))),
        )
        val http = OpenAiResponsesProvider.buildHttpRequest(
            ProviderRequest(original, userMessages(), JSONArray()),
        )
        val body = http.jsonBody()

        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(original))
        assertEquals("https://cli-chat-proxy.grok.com/v1/responses", http.url.toString())
        assertEquals("POST", http.method)
        assertEquals("text/event-stream", http.header("Accept"))
        assertEquals("Eta", http.header("User-Agent"))
        assertEquals("xai-grok-cli", http.header("X-XAI-Token-Auth"))
        assertTrue(http.header("x-grok-client-version").orEmpty().matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertEquals(original.model, body.getString("model"))
        assertEquals(body.getString("model"), http.header("x-grok-model-override"))
        assertNull(http.header("Authorization"))
        assertNull(http.header("originator"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("previous_response_id"))
        assertEquals("fixture", body.getJSONObject("metadata").getString("source"))
        assertEquals("测试请求", body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(
            listOf("web_search_call.action.sources", "reasoning.encrypted_content"),
            body.getJSONArray("include").strings(),
        )
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, original.openAiEndpointMode)
        assertEquals("https://api.x.ai/v1", original.baseUrl)
        assertEquals("unused-api-key-fixture", original.apiKey)
    }

    @Test
    fun completeToolCatalogPreservesObjectVariantsAndSelectsOnlyOneSearchImplementation() {
        val catalog = fullToolCatalog()
        val original = catalog.toString()
        val functions = catalog.objects().map { it.getJSONObject("function") }

        for (hostedSearch in listOf(false, true)) {
            val body = OpenAiResponsesProvider.buildHttpRequest(
                ProviderRequest(config().copy(hostedWebSearchEnabled = hostedSearch), userMessages(), catalog),
            ).jsonBody()
            val tools = body.getJSONArray("tools").objects()
            val projectedFunctions = tools.filter { it.getString("type") == "function" }
            val expectedFunctions = functions.filterNot { hostedSearch && it.getString("name") == "web_search" }

            assertEquals(
                expectedFunctions.map { it.getString("name") },
                projectedFunctions.map { it.getString("name") },
            )
            assertEquals(if (hostedSearch) 1 else 0, tools.count { it.getString("type") == "web_search" })
            for ((source, projected) in expectedFunctions.zip(projectedFunctions)) {
                assertEquals(source.getString("description"), projected.getString("description"))
                assertFalse(projected.getBoolean("strict"))
                val parameters = projected.getJSONObject("parameters")
                if (projected.getString("name") != "fetch_url") {
                    assertJsonEquals(source.getJSONObject("parameters"), parameters)
                }
                assertObjectParameters(projected.getString("name"), parameters)
            }
            val fetch = projectedFunctions.single { it.getString("name") == "fetch_url" }
                .getJSONObject("parameters")
            val variants = fetch.getJSONArray("oneOf").objects()
            assertEquals(
                setOf("url", "document_id"),
                variants.map { it.getJSONArray("required").getString(0) }.toSet(),
            )
            for (variant in variants) {
                val required = variant.getJSONArray("required").getString(0)
                assertTrue(variant.getJSONObject("properties").has(required))
                assertJsonEquals(fetch.getJSONObject("properties"), variant.getJSONObject("properties"))
                assertFalse(variant.getBoolean("additionalProperties"))
                assertTrue(variant.getJSONObject("properties").has("offset_chars"))
                assertTrue(variant.getJSONObject("properties").has("max_chars"))
            }
        }
        assertEquals(original, catalog.toString())
    }

    @Test
    fun nonChatPurposesCannotSendToolsOrCustomConversationOverrides() {
        val configured = config().copy(
            hostedWebSearchEnabled = true,
            extraBodyJson = """{"tools":[{"type":"web_search"}],"input":"overridden-input"}""",
            customBody = listOf(CustomBody("instructions", JsonPrimitive("overridden-instructions"))),
        )
        for (purpose in ProviderRequestPurpose.entries.filter { it != ProviderRequestPurpose.CHAT }) {
            val body = OpenAiResponsesProvider.buildHttpRequest(
                ProviderRequest(configured, userMessages(), fullToolCatalog(), purpose = purpose),
            ).jsonBody()

            assertFalse(purpose.name, body.has("tools"))
            assertFalse(purpose.name, body.has("tool_choice"))
            assertEquals("系统约束", body.getString("instructions"))
            assertEquals("测试请求", body.getJSONArray("input").getJSONObject(0).getString("content"))
            assertTrue(body.getJSONArray("include").strings().contains("reasoning.encrypted_content"))
        }
    }

    @Test
    fun streamedReasoningAndToolCallsReplayThroughRuntimeHistoryWithoutEnteringStorage() {
        val output = JSONArray("""[
          {"type":"reasoning","id":"rs_fixture","encrypted_content":"opaque-grok-fixture","summary":[]},
          {"type":"function_call","id":"fc_fixture","call_id":"call_fixture",
            "name":"fetch_url","arguments":"{\"url\":\"https://example.com\"}","status":"completed"}
        ]""")
        val terminal = JSONObject().put("type", "response.completed")
            .put("response", JSONObject().put("status", "completed").put("output", output))
        val assistant = OpenAiResponsesProvider.readStreamingResponse(
            "data: $terminal\n\n".byteInputStream(), AgentRunController(), {},
        )
        val calls = AgentConversationCodec.parseToolCalls(assistant)
        assertEquals("tool_calls", assistant.getString("finish_reason"))
        assertEquals("fetch_url", calls.single().name)
        val history = AgentConversationCodec.assistantHistoryMessage(assistant, calls)
        val result = AgentConversationCodec.toolResultMessage(
            calls.single(), AgentModelClient.ToolResult("网页正文 fixture"),
        )
        val messages = userMessages().put(history).put(result)
        val input = OpenAiResponsesProvider.buildHttpRequest(
            ProviderRequest(config(), messages, fullToolCatalog()),
        ).jsonBody().getJSONArray("input")

        assertEquals(
            listOf("message", "reasoning", "function_call", "function_call_output"),
            input.objects().map { it.getString("type") },
        )
        assertJsonEquals(output.getJSONObject(0), input.getJSONObject(1))
        assertJsonEquals(output.getJSONObject(1), input.getJSONObject(2))
        assertEquals("call_fixture", input.getJSONObject(3).getString("call_id"))
        assertEquals("网页正文 fixture", input.getJSONObject(3).getString("output"))
        val stored = AgentConversationCodec.encodeTranscriptForStorage(
            messages.objects().map(AgentConversationCodec::durableMessage),
        )
        assertFalse(stored.contains("opaque-grok-fixture"))
        assertFalse(stored.contains("_eta_responses_output_items"))
        val restored = AgentConversationCodec.decodeTranscript(stored)
        val restoredCall = JSONArray(restored[1].toolCallsJson).getJSONObject(0)
        assertEquals("fetch_url", restoredCall.getJSONObject("function").getString("name"))
        assertEquals("call_fixture", restored[2].toolCallId)
        assertEquals("网页正文 fixture", restored[2].content)
        assertEquals(
            "opaque-grok-fixture",
            ResponsesEphemeralState.outputItems(history)!!.getJSONObject(0).getString("encrypted_content"),
        )
    }

    @Test
    fun apiKeyResponsesKeepTheirOwnEndpointCredentialsAndRequestOptions() {
        for (baseUrl in listOf("https://api.x.ai/v1", "https://api.openai.com/v1", "https://gateway.example/v1")) {
            val apiConfig = config().copy(
                baseUrl = baseUrl,
                authMode = ProviderAuthModes.API_KEY,
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
                apiKey = "api-key-fixture",
                extraBodyJson = """{"temperature":0.2,"max_output_tokens":128}""",
            )
            val http = OpenAiResponsesProvider.buildHttpRequest(ProviderRequest(apiConfig, userMessages(), JSONArray()))
            val body = http.jsonBody()

            assertEquals("$baseUrl/responses", http.url.toString())
            assertEquals("Bearer api-key-fixture", http.header("Authorization"))
            assertNull(http.header("X-XAI-Token-Auth"))
            assertNull(http.header("x-grok-client-version"))
            assertNull(http.header("x-grok-model-override"))
            assertFalse(body.has("include"))
            assertEquals(0.2, body.getDouble("temperature"), 0.0)
            assertEquals(128, body.getInt("max_output_tokens"))
        }
    }

    @Test
    fun grokProjectionDoesNotChangeSharedToolsOrOtherResponsesConnections() {
        val catalog = fullToolCatalog()
        val original = catalog.toString()
        fun request(mode: String) = OpenAiResponsesProvider.buildHttpRequest(ProviderRequest(
            config().copy(authMode = mode, openAiEndpointMode = OpenAiEndpointMode.RESPONSES),
            userMessages(), catalog,
        )).jsonBody().getJSONArray("tools")
        val modes = listOf(ProviderAuthModes.API_KEY, ProviderAuthModes.OPENAI_CHATGPT)
        val before = modes.associateWith { request(it).toString() }

        request(ProviderAuthModes.XAI_GROK)

        assertEquals(original, catalog.toString())
        for (mode in modes) {
            val after = request(mode)
            assertEquals(before.getValue(mode), after.toString())
            for ((source, tool) in catalog.objects().zip(after.objects())) {
                assertEquals(source.getJSONObject("function").getJSONObject("parameters").toString(),
                    tool.getJSONObject("parameters").toString())
            }
        }
        val fetch = catalog.objects().map { it.getJSONObject("function") }
            .single { it.getString("name") == "fetch_url" }.getJSONObject("parameters")
        assertEquals(1, fetch.getJSONArray("oneOf").getJSONObject(0).length())
        assertFalse(fetch.getJSONArray("oneOf").getJSONObject(0).has("type"))
    }

    private fun config() = AgentModelClient.ModelConfig(
        providerId = "grok-account-fixture",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "unused-api-key-fixture",
        model = "grok-fixture",
        systemPrompt = "系统约束",
        authMode = ProviderAuthModes.XAI_GROK,
        openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
    )

    private fun fullToolCatalog(): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
    )

    private fun userMessages(): JSONArray = JSONArray().put(AgentConversationCodec.userTextMessage("测试请求"))

    private fun Request.jsonBody(): JSONObject {
        val buffer = Buffer()
        checkNotNull(body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(Json.parseToJsonElement(expected.toString()), Json.parseToJsonElement(actual.toString()))
    }

    private fun assertObjectParameters(name: String, schema: JSONObject) {
        assertEquals(name, "object", schema.getString("type"))
        for (key in listOf("oneOf", "anyOf")) {
            val variants = schema.optJSONArray(key) ?: continue
            assertTrue(name, variants.length() > 0)
            for (variant in variants.objects()) {
                assertEquals(name, "object", variant.getString("type"))
                val properties = variant.getJSONObject("properties")
                for (required in variant.optJSONArray("required")?.strings().orEmpty()) {
                    assertTrue("$name.$required", properties.has(required))
                }
            }
        }
    }
}
