package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesProviderTest {
    @Test
    fun newAdaptiveModelsHaveRoomForThinkingAndExplicitMaxTokensWins() {
        val body = event("message_stop", JSONObject())
        val cases = listOf(
            Triple("claude-fable-5-1", emptyList<CustomBody>(), 16_384),
            Triple("claude-opus-5-5", emptyList<CustomBody>(), 16_384),
            Triple("claude-sonnet-5", emptyList<CustomBody>(), 4096),
            Triple("claude-fable-5-1", listOf(CustomBody("max_tokens", JsonPrimitive(8192))), 8192),
        )
        cases.forEach { (model, customBody, expectedMaxTokens) ->
            val requestBody = AtomicReference<String>()
            withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
                val fixture = providerRequest(baseUrl)
                AnthropicMessagesProvider.complete(
                    fixture.copy(config = fixture.config.copy(
                        model = model,
                        customBody = customBody,
                        reasoningEffort = if (customBody.isEmpty()) ReasoningEffort.DEFAULT else ReasoningEffort.XHIGH,
                    )),
                    AgentRunController(),
                )
            }
            assertEquals(expectedMaxTokens, JSONObject(requestBody.get()).getInt("max_tokens"))
        }
    }

    @Test
    fun toolResultRequestReplaysOrderedThinkingAndRedactedBlocksWithoutPersistingThem() {
        val firstResponse = buildString {
            append(blockStart(0, JSONObject().put("type", "thinking").put("thinking", "先判断")))
            append(blockDelta(0, JSONObject().put("type", "thinking_delta").put("thinking", "所需信息")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "signature-")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "one")))
            append(blockStop(0))
            append(blockStart(1, JSONObject().put("type", "text").put("text", "开始查询")))
            append(blockStop(1))
            append(blockStart(2, JSONObject().put("type", "tool_use").put("id", "toolu_one")
                .put("name", "device_info").put("input", JSONObject())))
            append(blockDelta(2, JSONObject().put("type", "input_json_delta").put("partial_json", "{\"detail\":true}")))
            append(blockStop(2))
            append(blockStart(3, JSONObject().put("type", "redacted_thinking").put("data", "redacted-one")))
            append(blockStop(3))
            append(blockStart(4, JSONObject().put("type", "thinking").put("thinking", "")))
            append(blockDelta(4, JSONObject().put("type", "signature_delta").put("signature", "signature-two")))
            append(blockStop(4))
            append(blockStart(5, JSONObject().put("type", "tool_use").put("id", "toolu_two")
                .put("name", "get_current_context").put("input", JSONObject())))
            append(blockDelta(5, JSONObject().put("type", "input_json_delta").put("partial_json", "{}")))
            append(blockStop(5))
            append(event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "tool_use"))))
            append(event("message_stop", JSONObject()))
        }
        lateinit var assistant: JSONObject
        withAnthropicServer(firstResponse) { baseUrl ->
            assistant = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController())
                .assistantMessage
        }
        val calls = AgentConversationCodec.parseToolCalls(assistant)
        val assistantHistory = AgentConversationCodec.assistantHistoryMessage(assistant, calls)
        val durable = AgentConversationCodec.encodeTranscriptForStorage(
            listOf(AgentConversationCodec.durableMessage(assistantHistory)),
        )
        assertFalse(durable.contains("signature-one"))
        assertFalse(durable.contains("signature-two"))
        assertFalse(durable.contains("redacted-one"))

        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("读取设备信息和当前上下文"))
            .put(assistantHistory)
            .put(AgentConversationCodec.toolResultMessage(calls[0], AgentModelClient.ToolResult("设备信息")))
            .put(AgentConversationCodec.toolResultMessage(calls[1], AgentModelClient.ToolResult("当前上下文")))
        val secondResponse = blockStart(0, JSONObject().put("type", "text").put("text", "已完成")) +
            blockStop(0) + event("message_stop", JSONObject())
        val requestBody = AtomicReference<String>()
        withAnthropicServer(secondResponse, onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                providerRequest(baseUrl).copy(messages = messages),
                AgentRunController(),
            )
        }

        val requestMessages = JSONObject(requestBody.get()).getJSONArray("messages")
        val blocks = requestMessages.getJSONObject(1).getJSONArray("content")
        assertEquals(listOf("thinking", "text", "tool_use", "redacted_thinking", "thinking", "tool_use"),
            (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
        assertEquals("先判断所需信息", blocks.getJSONObject(0).getString("thinking"))
        assertEquals("signature-one", blocks.getJSONObject(0).getString("signature"))
        assertEquals("开始查询", blocks.getJSONObject(1).getString("text"))
        assertTrue(blocks.getJSONObject(2).getJSONObject("input").getBoolean("detail"))
        assertEquals("redacted-one", blocks.getJSONObject(3).getString("data"))
        assertEquals("", blocks.getJSONObject(4).getString("thinking"))
        assertEquals("signature-two", blocks.getJSONObject(4).getString("signature"))
        assertEquals("toolu_two", blocks.getJSONObject(5).getString("id"))
        assertEquals(3, requestMessages.length())
        val toolResults = requestMessages.getJSONObject(2).getJSONArray("content")
        assertEquals("toolu_one", toolResults.getJSONObject(0).getString("tool_use_id"))
        assertEquals("toolu_two", toolResults.getJSONObject(1).getString("tool_use_id"))
        assertFalse(requestBody.get().contains("_eta_anthropic_content_blocks"))
    }

    @Test
    fun inputSchemaDropsTopLevelCombinatorsButKeepsNestedOnesAndLocalSchema() {
        val tools = JSONArray().also { array ->
            AgentWebToolCatalog.appendTo(array)
            array.put(AgentToolSchema.function(
                name = "nested",
                description = "嵌套组合子",
                parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("value", JSONObject().put("anyOf", JSONArray()
                        .put(JSONObject().put("type", "string"))
                        .put(JSONObject().put("type", "integer"))))),
            ))
        }
        val original = tools.toString()
        val requestBody = AtomicReference<String>()
        withAnthropicServer(event("message_stop", JSONObject()), onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(providerRequest(baseUrl).copy(tools = tools), AgentRunController())
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("tools")
        (0 until sent.length()).map { sent.getJSONObject(it).getJSONObject("input_schema") }.forEach { schema ->
            listOf("oneOf", "anyOf", "allOf").forEach { assertFalse(schema.has(it)) }
            assertEquals("object", schema.getString("type"))
        }
        val fetchUrl = (0 until sent.length()).map(sent::getJSONObject).single { it.getString("name") == "fetch_url" }
        assertTrue(fetchUrl.getJSONObject("input_schema").getJSONObject("properties").has("document_id"))
        val nested = (0 until sent.length()).map(sent::getJSONObject).single { it.getString("name") == "nested" }
        assertTrue(nested.getJSONObject("input_schema").getJSONObject("properties")
            .getJSONObject("value").has("anyOf"))
        assertEquals(original, tools.toString())
    }

    @Test
    fun parallelToolResultsAndObservationShareOneUserTurnWithResultsFirst() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put("tool_calls", JSONArray()
                .put(JSONObject().put("id", "toolu_a").put("type", "function")
                    .put("function", JSONObject().put("name", "device_info").put("arguments", "{}")))
                .put(JSONObject().put("id", "toolu_b").put("type", "function")
                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))))
        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("开始"))
            .put(assistant)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_a").put("content", "A"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_b").put("content", "B"))
            .put(AgentConversationCodec.userTextMessage("用户补充"))
        val requestBody = AtomicReference<String>()
        withAnthropicServer(event("message_stop", JSONObject()), onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(providerRequest(baseUrl).copy(messages = messages), AgentRunController())
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"), (0 until sent.length()).map { sent.getJSONObject(it).getString("role") })
        val turn = sent.getJSONObject(2).getJSONArray("content")
        assertEquals(listOf("tool_result", "tool_result", "text"), (0 until turn.length()).map { turn.getJSONObject(it).getString("type") })
        assertEquals("toolu_b", turn.getJSONObject(1).getString("tool_use_id"))
        assertEquals("用户补充", turn.getJSONObject(2).getString("text"))
    }

    @Test
    fun usageReportsContextOccupancyAndKeepsInputWhenDeltaOnlyCarriesOutput() {
        val body = event("message_start", JSONObject().put("message", JSONObject().put("usage", JSONObject()
            .put("input_tokens", 100).put("cache_read_input_tokens", 900).put("cache_creation_input_tokens", 50)
            .put("output_tokens", 1)))) +
            blockStart(0, JSONObject().put("type", "text").put("text", "好")) + blockStop(0) +
            event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn"))
                .put("usage", JSONObject().put("output_tokens", 42))) +
            event("message_stop", JSONObject())
        withAnthropicServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            val last = events.filterIsInstance<ProviderEvent.Usage>().last()
            assertEquals(1_050, last.usage.contextTokens)
            assertEquals(1_050, last.contextInputTokens)
            assertEquals(100, last.usage.inputTokens)
            assertEquals(900, last.usage.cachedTokens)
            assertEquals(42, last.usage.outputTokens)
        }
    }

    @Test
    fun refusalStopDetailsReachAssistantMessage() {
        val body = blockStart(0, JSONObject().put("type", "text").put("text", "部分")) + blockStop(0) +
            event("message_delta", JSONObject().put("delta", JSONObject()
                .put("stop_reason", "refusal")
                .put("stop_details", JSONObject().put("type", "refusal").put("category", JSONObject.NULL)
                    .put("explanation", "说明")))) +
            event("message_stop", JSONObject())
        withAnthropicServer(body) { baseUrl ->
            val response = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController())
            assertEquals(AssistantStopReason.CONTENT_FILTER, response.stopReason)
            assertEquals("说明", response.assistantMessage.getJSONObject("stop_details").getString("explanation"))
        }
    }

    @Test
    fun completedToolRoundsDoNotReplayOldThinkingSignatures() {
        fun toolAssistant(id: String, signature: String) = JSONObject()
            .put("role", "assistant")
            .put("content", "读取")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", "device_info").put("arguments", "{}"))))
            .also { message ->
                AnthropicEphemeralState.attachContentBlocks(message, JSONArray()
                    .put(JSONObject().put("type", "thinking").put("thinking", "")
                        .put("signature", signature))
                    .put(JSONObject().put("type", "tool_use").put("id", id)
                        .put("name", "device_info").put("input", JSONObject())))
            }
        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("旧问题"))
            .put(toolAssistant("toolu_old", "OLD_SIGNATURE"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_old")
                .put("content", "旧结果"))
            .put(JSONObject().put("role", "assistant").put("content", "旧回答"))
            .put(AgentConversationCodec.userTextMessage("新问题"))
            .put(toolAssistant("toolu_current", "CURRENT_SIGNATURE"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_current")
                .put("content", "新结果"))
        val requestBody = AtomicReference<String>()
        withAnthropicServer(event("message_stop", JSONObject()), onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                providerRequest(baseUrl).copy(messages = messages),
                AgentRunController(),
            )
        }

        val body = requestBody.get()
        assertFalse(body.contains("OLD_SIGNATURE"))
        assertTrue(body.contains("CURRENT_SIGNATURE"))
        val sent = JSONObject(body).getJSONArray("messages")
        assertEquals("tool_use", sent.getJSONObject(1).getJSONArray("content")
            .getJSONObject(1).getString("type"))
        assertEquals("thinking", sent.getJSONObject(5).getJSONArray("content")
            .getJSONObject(0).getString("type"))
    }

    @Test
    fun thinkingStreamsThroughToolBoundariesAndCompletesBeforeConnectionCloses() {
        val firstDelta = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val serverObservedCompletion = CountDownLatch(1)
        val completedBeforeClose = AtomicBoolean(false)
        val firstChunk = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "开始分析，"))
        val remaining = buildString {
            append(blockDelta(0, JSONObject().put("type", "thinking_delta").put("thinking", "需要工具。")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "opaque-signature")))
            append(blockStop(0))
            append(blockStop(0))
            append(event("ping", JSONObject()))
            append(blockStart(1, JSONObject().put("type", "tool_use").put("id", "tool_1")
                .put("name", "device_info").put("input", JSONObject())))
            append(blockDelta(1, JSONObject().put("type", "input_json_delta").put("partial_json", "{}")))
            append(blockStop(1))
            append(blockStart(2, JSONObject().put("type", "thinking").put("thinking", "")))
            append(blockDelta(2, JSONObject().put("type", "thinking_delta").put("thinking", "继续分析。")))
            append(blockDelta(2, JSONObject().put("type", "signature_delta").put("signature", "opaque-signature-two")))
            append(blockStop(2))
            append(blockStart(3, JSONObject().put("type", "text").put("text", "最终答案")))
            append(blockStop(3))
            append(event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "tool_use"))))
            append(event("message_stop", JSONObject()))
        }

        withAnthropicServer(
            body = "",
            writeBody = { output ->
                output.write(firstChunk.toByteArray())
                output.flush()
                check(firstDelta.await(5, TimeUnit.SECONDS))
                output.write(remaining.toByteArray())
                output.flush()
                completedBeforeClose.set(completed.await(5, TimeUnit.SECONDS))
                serverObservedCompletion.countDown()
            },
        ) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController()) { event ->
                events += event
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING) {
                    firstDelta.countDown()
                }
                if (event is ProviderEvent.Completed) completed.countDown()
            }

            assertTrue(serverObservedCompletion.await(5, TimeUnit.SECONDS))
            assertTrue(completedBeforeClose.get())
            assertEquals("开始分析，需要工具。继续分析。", response.assistantMessage.getString("reasoning_content"))
            assertEquals("最终答案", response.assistantMessage.getString("content"))
            val ends = events.filterIsInstance<ProviderEvent.BlockEnd>()
            assertEquals(listOf(0, 1, 2, 3), ends.map { it.index })
            assertEquals(listOf("开始分析，需要工具。", "{}", "继续分析。", "最终答案"), ends.map { it.content })
            assertEquals("tool_1", response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals(listOf("开始分析，", "需要工具。", "继续分析。"), events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta })
        }
    }

    @Test
    fun signatureOnlyAndRedactedThinkingDoNotBecomeVisibleText() {
        val body = buildString {
            append(blockStart(0, JSONObject().put("type", "thinking").put("thinking", "")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "opaque-signature")))
            append(blockStop(0))
            append(blockStart(1, JSONObject().put("type", "redacted_thinking").put("data", "opaque-data")))
            append(blockStop(1))
            append(blockStart(2, JSONObject().put("type", "text").put("text", "答案")))
            append(blockStop(2))
            append(event("message_stop", JSONObject()))
        }
        withAnthropicServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            assertEquals("", response.assistantMessage.getString("reasoning_content"))
            assertEquals("答案", response.assistantMessage.getString("content"))
            assertTrue(events.filterIsInstance<ProviderEvent.BlockDelta>().none { it.kind == AssistantBlockKind.THINKING })
        }
    }

    @Test
    fun rejectsUnclosedThinkingBlockEvenWhenMessageStopArrives() {
        val body = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "部分内容")) +
            event("message_stop", JSONObject())
        withAnthropicServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val failure = runCatching {
                AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("内容块未正常结束"))
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
    }

    @Test
    fun rejectsToolRoundWhoseThinkingBlockHasNoSignature() {
        val body = buildString {
            append(blockStart(0, JSONObject().put("type", "thinking").put("thinking", "需要调用工具")))
            append(blockStop(0))
            append(blockStart(1, JSONObject().put("type", "tool_use").put("id", "toolu_1")
                .put("name", "device_info").put("input", JSONObject())))
            append(blockStop(1))
            append(event("message_stop", JSONObject()))
        }
        withAnthropicServer(body) { baseUrl ->
            val failure = runCatching {
                AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController())
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("缺少思考签名"))
        }
    }

    @Test
    fun thinkingStreamErrorsAndEarlyEofDoNotReportCompletion() {
        val prefix = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "部分思考"))
        val endings = listOf(
            "" to "未正常结束",
            event("error", JSONObject().put("error", JSONObject().put("type", "overloaded_error"))) to "返回错误",
        )
        endings.forEach { (ending, expectedError) ->
            withAnthropicServer(prefix + ending) { baseUrl ->
                val events = mutableListOf<ProviderEvent>()
                val failure = runCatching {
                    AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
                }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains(expectedError))
                assertEquals("部分思考", events.filterIsInstance<ProviderEvent.BlockDelta>().single().delta)
                assertFalse(events.any { it is ProviderEvent.Completed })
            }
        }
    }

    @Test
    fun completeParsesTextAndToolUseStream() {
        val body = buildString {
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 0)
                .put("content_block", JSONObject().put("type", "text"))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "Hello"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 0)))
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 1)
                .put("content_block", JSONObject()
                    .put("type", "tool_use")
                    .put("id", "toolu_1")
                    .put("name", "observe_screen")
                    .put("input", JSONObject()))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 1)
                .put("delta", JSONObject()
                    .put("type", "input_json_delta")
                    .put("partial_json", "{\"include_screenshot\":true}"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 1)))
            append(event("message_delta", JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "tool_use"))
                .put("usage", JSONObject().put("input_tokens", 4).put("output_tokens", 2))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray().put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", "observe_screen")
                                    .put("description", "observe")
                                    .put("parameters", JSONObject().put("type", "object"))
                            )
                    )
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            val toolCall = response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("toolu_1", toolCall.getString("id"))
            assertEquals("observe_screen", toolCall.getJSONObject("function").getString("name"))
            assertEquals(
                "{\"include_screenshot\":true}",
                toolCall.getJSONObject("function").getString("arguments")
            )
            assertTrue(requestBody.get().contains("\"tools\""))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
            assertEquals(
                listOf(
                    "start:TEXT:0",
                    "delta:TEXT:0:Hello",
                    "end:TEXT:0",
                    "start:TOOL_CALL:1",
                    "delta:TOOL_CALL:1:{\"include_screenshot\":true}",
                    "end:TOOL_CALL:1",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
            assertEquals(1, events.filterIsInstance<ProviderEvent.Usage>().size)
        }
    }

    @Test
    fun completeBuildsAdaptiveThinkingRequestWhenEnabled() {
        val body = buildString {
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        providerSourceType = "anthropic",
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.MEDIUM,
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
            assertEquals("medium", request.getJSONObject("output_config").getString("effort"))
        }
    }

    private fun providerRequest(baseUrl: String) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerType = ProviderTypes.ANTHROPIC,
            baseUrl = baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        ),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "测试推理")),
        tools = JSONArray(),
    )

    private fun blockStart(index: Int, content: JSONObject) =
        event("content_block_start", JSONObject().put("index", index).put("content_block", content))

    private fun blockDelta(index: Int, delta: JSONObject) =
        event("content_block_delta", JSONObject().put("index", index).put("delta", delta))

    private fun blockStop(index: Int) = event("content_block_stop", JSONObject().put("index", index))

    private fun event(name: String, data: JSONObject): String =
        "event: $name\ndata: ${JSONObject(data.toString()).put("type", name)}\n\n"

    private fun withAnthropicServer(
        body: String,
        onRequest: (String) -> Unit = {},
        writeBody: ((OutputStream) -> Unit)? = null,
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/messages") { exchange ->
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, if (writeBody == null) bytes.size.toLong() else 0)
            exchange.responseBody.use { output ->
                if (writeBody == null) output.write(bytes) else writeBody(output)
            }
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
