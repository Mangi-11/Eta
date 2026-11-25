package io.github.mangi.eta.agent.model.provider.openai

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AssistantBlockKind
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest

class OpenAiChatToolCallIdsTest {
    @Test
    fun emptyIdDeltasPreserveTheReceivedId() {
        listOf(null, JSONObject.NULL, "", " \t").forEach { laterId ->
            val body = sse(delta(0, "call_original", "fixture_tool", "{")) +
                sse(delta(0, laterId, arguments = "}"), finish = true) + DONE
            withProvider({ body }) { request ->
                val (message, events) = complete(request)
                val call = message.getJSONArray("tool_calls").getJSONObject(0)
                assertEquals("call_original", call.getString("id"))
                assertEquals("{}", call.getJSONObject("function").getString("arguments"))
                assertConsistentIds(message, events)
            }
        }
    }

    @Test
    fun acceptsAnIdThatArrivesAfterTheFirstDelta() {
        val body = sse(delta(0, null, "fixture_tool", "{")) +
            sse(delta(0, "call_late", arguments = "}"), finish = true) + DONE
        withProvider({ body }) { request ->
            val (message, events) = complete(request)
            assertEquals("call_late", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertConsistentIds(message, events)
        }
    }

    @Test
    fun nullAndBlankInitialIdsReceiveConsistentNonBlankIds() {
        listOf(JSONObject.NULL, "", " \t").forEach { id ->
            val body = sse(delta(0, id, "fixture_tool", "{}"), finish = true) + DONE
            withProvider({ body }) { request ->
                val (message, events) = complete(request)
                assertConsistentIds(message, events)
            }
        }
    }

    @Test
    fun missingIdsStayDistinctAcrossResponsesAndMatchEventsAndHistory() {
        val body = sse(delta(0, null, "fixture_tool", "{}"), finish = true) + DONE
        withProvider({ body }) { request ->
            val (first, firstEvents) = complete(request)
            val firstCalls = AgentConversationCodec.parseToolCalls(first)
            val history = JSONArray(request.messages.toString())
                .put(AgentConversationCodec.assistantHistoryMessage(first, firstCalls))
                .put(AgentConversationCodec.toolResultMessage(firstCalls.single(), AgentModelClient.ToolResult("完成")))
            val (second, secondEvents) = complete(request.copy(messages = history))
            val secondId = AgentConversationCodec.parseToolCalls(second).single().id

            assertNotEquals(firstCalls.single().id, secondId)
            assertConsistentIds(first, firstEvents)
            assertConsistentIds(second, secondEvents)
            val secondCalls = AgentConversationCodec.parseToolCalls(second)
            assertEquals(secondId, AgentConversationCodec.assistantHistoryMessage(second, secondCalls)
                .getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals(secondId, AgentConversationCodec.toolResultMessage(secondCalls.single(),
                AgentModelClient.ToolResult("完成")).getString("tool_call_id"))
        }
    }

    @Test
    fun resolvesDuplicateAndHistoricalIdsWithoutMixingUnorderedIndexes() {
        val calls = JSONArray()
            .put(delta(7, "call_duplicate", "seventh", "{}"))
            .put(delta(2, "call_duplicate", "second", "{}"))
            .put(delta(4, "call_history", "fourth", "{}"))
            .put(delta(0, "call_unique", "zeroth", "{}"))
        val body = sse(calls, finish = true) + DONE
        withProvider({ body }) { request ->
            val history = request.messages
            appendBatch(history, "call_history")
            val (message, events) = complete(request)
            val parsed = AgentConversationCodec.parseToolCalls(message)

            assertEquals(listOf("zeroth", "second", "fourth", "seventh"), parsed.map { it.name })
            assertEquals("call_unique", parsed[0].id)
            assertEquals("call_duplicate", parsed[1].id)
            assertEquals(4, parsed.map { it.id }.toSet().size)
            assertFalse(parsed.any { it.id == "call_history" })
            assertConsistentIds(message, events)
        }
    }

    @Test
    fun reservesIdsIntroducedByTheOutgoingHistoryRepair() {
        val sentIds = AtomicReference<Set<String>>()
        withProvider({ body ->
            val messages = body.getJSONArray("messages")
            val originalId = messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id")
            val repairedId = messages.getJSONObject(3).getJSONArray("tool_calls").getJSONObject(0).getString("id")
            sentIds.set(setOf(originalId, repairedId))
            sse(JSONArray()
                .put(delta(0, originalId, "original_collision", "{}"))
                .put(delta(1, repairedId, "repaired_collision", "{}")), finish = true) + DONE
        }) { request ->
            appendBatch(request.messages, "call_old")
            appendBatch(request.messages, "call_old")
            val before = request.messages.toString()
            val (message, events) = complete(request)

            assertEquals(2, sentIds.get().size)
            val newIds = AgentConversationCodec.parseToolCalls(message).map { it.id }
            assertTrue(newIds.none { it in sentIds.get() })
            assertEquals(before, request.messages.toString())
            assertConsistentIds(message, events)
        }
    }

    @Test
    fun reservesOriginalHistoryIdsWhenCustomMessagesReplaceTheWireHistory() {
        val body = sse(delta(0, "call_original", "fixture_tool", "{}"), finish = true) + DONE
        withProvider({ body }) { request ->
            appendBatch(request.messages, "call_original")
            val replacement = JSONObject().put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", "替代请求")))
            val (message, events) = complete(request.copy(
                config = request.config.copy(extraBodyJson = replacement.toString()),
            ))
            assertNotEquals("call_original", AgentConversationCodec.parseToolCalls(message).single().id)
            assertConsistentIds(message, events)
        }
    }

    private fun complete(request: ProviderRequest): Pair<JSONObject, List<ProviderEvent>> {
        val events = mutableListOf<ProviderEvent>()
        val response = OpenAiChatCompletionsProvider.complete(request, AgentRunController(), events::add)
        return response.assistantMessage to events
    }

    private fun assertConsistentIds(message: JSONObject, events: List<ProviderEvent>) {
        val calls = AgentConversationCodec.parseToolCalls(message)
        val rawCalls = message.getJSONArray("tool_calls")
        assertEquals(calls.map { it.id }, (0 until rawCalls.length()).map { rawCalls.getJSONObject(it).getString("id") })
        assertTrue(calls.all { it.id.isNotBlank() })
        assertEquals(calls.size, calls.map { it.id }.toSet().size)
        val ends = events.filterIsInstance<ProviderEvent.BlockEnd>().filter { it.kind == AssistantBlockKind.TOOL_CALL }
        assertEquals(calls.size, ends.size)
        assertEquals(calls.associate { it.name to it.id }, ends.associate { it.name to it.blockId })
    }

    private fun appendBatch(messages: JSONArray, id: String) {
        messages.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", "fixture_tool").put("arguments", "{}")),
        )))
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "完成"))
    }

    private fun delta(index: Int, id: Any?, name: String? = null, arguments: String): JSONObject =
        JSONObject().put("index", index).apply {
            if (id != null) put("id", id)
            put("function", JSONObject().put("arguments", arguments).apply {
                if (name != null) put("name", name)
            })
        }

    private fun sse(call: JSONObject, finish: Boolean = false): String = sse(JSONArray().put(call), finish)

    private fun sse(calls: JSONArray, finish: Boolean): String = "data: ${JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("tool_calls", calls))
            .put("finish_reason", if (finish) "tool_calls" else JSONObject.NULL),
    ))}\n\n"

    private fun withProvider(body: (JSONObject) -> String, block: (ProviderRequest) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            val request = exchange.requestBody.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            val response = body(request).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            block(ProviderRequest(
                config = AgentModelClient.ModelConfig(
                    providerSourceType = "custom",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "", model = "fixture-model", systemPrompt = "",
                ),
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "测试")),
                tools = JSONArray(),
            ))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        const val DONE = "data: [DONE]\n\n"
    }
}
