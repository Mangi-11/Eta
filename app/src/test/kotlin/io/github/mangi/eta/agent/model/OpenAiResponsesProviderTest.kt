package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesProviderTest {
    @Test
    fun nativeReasoningDeltasArriveBeforeDoneAndTypedFinalContentIsNotDuplicated() {
        val firstDeltaDelivered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val serverObservedCompletion = CountDownLatch(1)
        val completedBeforeClose = AtomicBoolean(false)
        val reasoningText = "先分析输入，再整理答案。"
        val nativeItem = JSONObject()
            .put("id", "rs_native")
            .put("type", "reasoning")
            .put("summary", JSONArray())
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", reasoningText)),
            )
        fun reasoningEvent(type: String, key: String, text: String) = event(
            type,
            JSONObject().put("item_id", "rs_native")
                .put("output_index", 0).put("content_index", 0).put(key, text),
        )
        val firstChunk = reasoningEvent("response.reasoning_text.delta", "delta", "先分析输入，")
        val remainingChunks = buildString {
            append(reasoningEvent("response.reasoning_text.delta", "delta", "再整理答案。"))
            append(reasoningEvent("response.reasoning_text.done", "text", reasoningText))
            append(event("response.output_item.done", JSONObject().put("output_index", 0).put("item", nativeItem)))
            append(responseTextEvent("response.output_text.delta", "msg_1", 1, "delta", "答案"))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed")
                            .put("output", JSONArray().put(nativeItem).put(messageItem("msg_1", "答案"))),
                    ),
                ),
            )
        }

        withSseServer(
            body = "",
            writeBody = { output ->
                output.write(firstChunk.toByteArray(Charsets.UTF_8))
                output.flush()
                // 服务端只有确认首个增量已经交付，才发送 done 和终态，排除读到 EOF 才输出。
                check(firstDeltaDelivered.await(5, TimeUnit.SECONDS))
                output.write(remainingChunks.toByteArray(Charsets.UTF_8))
                output.flush()
                completedBeforeClose.set(completed.await(5, TimeUnit.SECONDS))
                serverObservedCompletion.countDown()
            },
        ) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "测试推理")),
                    JSONArray(),
                ),
                AgentRunController(),
            ) { event ->
                events += event
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING) {
                    firstDeltaDelivered.countDown()
                }
                if (event is ProviderEvent.Completed) completed.countDown()
            }

            assertTrue(serverObservedCompletion.await(5, TimeUnit.SECONDS))
            assertTrue(completedBeforeClose.get())
            assertEquals(
                listOf("先分析输入，", "再整理答案。"),
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta },
            )
            val end = events.filterIsInstance<ProviderEvent.BlockEnd>()
                .filter { it.kind == AssistantBlockKind.THINKING }.single()
            assertEquals(reasoningText, end.content)
            assertFalse(end.replaceContent)
            assertEquals(reasoningText, result.assistantMessage.getString("reasoning_content"))
            assertEquals("答案", result.assistantMessage.getString("content"))
            assertEquals(
                nativeItem.toString(),
                ResponsesEphemeralState.outputItems(result.assistantMessage)?.getJSONObject(0)?.toString(),
            )
        }
    }

    @Test
    fun typedReasoningPartsKeepTheirIdentityAndOverrideLegacyAlias() {
        val item = JSONObject().put("id", "rs_parts").put("type", "reasoning")
            .put("reasoning_text", "旧字段不应重复追加")
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "reasoning_text").put("text", "第一段"))
                    .put(JSONObject().put("type", "reasoning_text").put("text", "第二段补全")),
            )
        val body = buildString {
            append(
                event(
                    "response.reasoning_text.delta",
                    JSONObject().put("item_id", "rs_parts").put("output_index", 0)
                        .put("content_index", 1).put("delta", "第二段"),
                ),
            )
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", JSONArray().put(item)),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "测试推理")),
                    JSONArray(),
                ),
                AgentRunController(),
                events::add,
            )

            assertEquals("第一段\n第二段补全", result.assistantMessage.getString("reasoning_content"))
            val corrections = events.filterIsInstance<ProviderEvent.BlockEnd>().filter { it.replaceContent }
            assertEquals(1, corrections.size)
            assertEquals(0, corrections.single().index)
            assertEquals("第二段补全", corrections.single().content)
        }
    }

    @Test
    fun requestUsesTypedInputProtectedFieldsAndOptionalHostedSearch() {
        val assistant = JSONObject().put("role", "assistant").put("content", "先检查")
        ResponsesEphemeralState.attachOutputItems(
            assistant,
            JSONArray().put(
                JSONObject()
                    .put("id", "rs_1")
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque"),
            ),
        )
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "看图"))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/png;base64,AA==")),
                        ),
                ),
            )
            .put(assistant)
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call_1")
                    .put("content", "{\"ok\":true}"),
            )
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "device_info")
                    .put("description", "读取设备")
                    .put("parameters", JSONObject().put("type", "object")),
            ),
        )

        val request = OpenAiResponsesProvider.buildRequestJson(
            config = config("https://example.com/v1").copy(
                hostedWebSearchEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                    canDisable = true,
                ),
                reasoningEffort = ReasoningEffort.HIGH,
                extraBodyJson = """{"model":"wrong","store":true,"metadata":{"source":"eta"}}""",
                customBody = listOf(CustomBody("stream", JsonPrimitive(false))),
            ),
            messages = messages,
            tools = tools,
        )

        assertEquals("test-model", request.getString("model"))
        assertEquals("系统提示", request.getString("instructions"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.getBoolean("store"))
        assertFalse(request.has("previous_response_id"))
        assertEquals("eta", request.getJSONObject("metadata").getString("source"))
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", request.getJSONObject("reasoning").getString("summary"))
        assertEquals("input_image", request.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("reasoning", request.getJSONArray("input").getJSONObject(1).getString("type"))
        assertEquals("function_call_output", request.getJSONArray("input").getJSONObject(2).getString("type"))
        assertFalse(request.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
        assertEquals("web_search", request.getJSONArray("tools").getJSONObject(1).getString("type"))
    }

    @Test
    fun requestUsesMergedInstructionsAndTypedDurableHistoryMessages() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "基础约束"))
            .put(JSONObject().put("role", "user").put("content", "旧问题"))
            .put(JSONObject().put("role", "system").put("content", "压缩上下文"))
            .put(JSONObject().put("role", "assistant").put("content", "旧回答"))
            .put(JSONObject().put("role", "user").put("content", "当前问题"))

        val request = OpenAiResponsesProvider.buildRequestJson(
            config = config("https://example.com/v1"),
            messages = messages,
            tools = JSONArray(),
        )

        assertEquals("基础约束\n\n压缩上下文", request.getString("instructions"))
        val input = request.getJSONArray("input")
        assertEquals(listOf("user", "assistant", "user"), input.roles())
        assertEquals(listOf("message", "message", "message"), input.types())
        assertEquals("旧回答", input.getJSONObject(1).getString("content"))
    }

    @Test
    fun completeUsesTerminalOutputAsAuthorityAndEmitsSemanticEvents() {
        val terminalOutput = JSONArray()
            .put(
                JSONObject()
                    .put("id", "rs_1")
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque")
                    .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "完整摘要"))),
            )
            .put(JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"))
            .put(
                JSONObject()
                    .put("id", "msg_1")
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", "你好世界")
                                .put(
                                    "annotations",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "url_citation")
                                            .put("start_index", 2)
                                            .put("end_index", 4)
                                            .put("url", "https://example.com/source")
                                            .put("title", "来源"),
                                    ),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("id", "fc_1")
                    .put("type", "function_call")
                    .put("call_id", "call_1")
                    .put("name", "device_info")
                    .put("arguments", "{\"detail\":true}"),
            )
        val response = JSONObject()
            .put("status", "completed")
            .put("output", terminalOutput)
            .put(
                "usage",
                JSONObject()
                    .put("input_tokens", 10)
                    .put("output_tokens", 8)
                    .put("total_tokens", 18)
                    .put("input_tokens_details", JSONObject().put("cached_tokens", 3))
                    .put("output_tokens_details", JSONObject().put("reasoning_tokens", 5)),
            )
        val body = buildString {
            append(event("response.reasoning_summary_text.delta", JSONObject().put("delta", "完整")))
            append(
                event(
                    "response.output_item.added",
                    JSONObject().put("item", JSONObject().put("id", "ws_1").put("type", "web_search_call")),
                ),
            )
            append(
                event(
                    "response.output_item.done",
                    JSONObject().put(
                        "item",
                        JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"),
                    ),
                ),
            )
            append(event("response.output_text.delta", JSONObject().put("delta", "你好")))
            append(event("response.completed", JSONObject().put("response", response)))
        }
        val requestBody = AtomicReference<String>()

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request = ProviderRequest(
                    config = config(baseUrl),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            assertTrue(requestBody.get().contains("\"store\":false"))
            assertEquals("完整摘要", result.assistantMessage.getString("reasoning_content"))
            assertTrue(result.assistantMessage.getString("content").contains("[[1]]"))
            assertEquals("call_1", result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertNotNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
            assertEquals(1, events.filterIsInstance<ProviderEvent.HostedToolStarted>().size)
            assertEquals(1, events.filterIsInstance<ProviderEvent.HostedToolFinished>().size)
            val usage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, usage.contextTokens)
            assertEquals(3, usage.cachedTokens)
            assertEquals(5, usage.reasoningTokens)
        }
    }

    @Test
    fun completeUsesStreamedTextWhenCompletedOutputIsEmpty() {
        val body = buildString {
            append(event("response.reasoning_summary_text.delta", JSONObject().put("delta", "简要分析")))
            append(event("response.output_text.delta", JSONObject().put("delta", "你好")))
            append(event("response.output_text.delta", JSONObject().put("delta", "世界")))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed"),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "你好")),
                    JSONArray(),
                ),
                AgentRunController(),
            )

            assertEquals("你好世界", result.assistantMessage.getString("content"))
            assertEquals("简要分析", result.assistantMessage.getString("reasoning_content"))
            assertEquals("stop", result.assistantMessage.getString("finish_reason"))
            assertNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
        }
    }

    @Test
    fun completePreservesInterleavedResponseItemOrderAndBlockIdentity() {
        val terminalOutput = JSONArray()
            .put(reasoningItem("rs_1", "先判断"))
            .put(messageItem("msg_1", "我先查一下。"))
            .put(JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"))
            .put(reasoningItem("rs_2", "整理结果"))
            .put(messageItem("msg_2", "这是最终答案。"))
        val body = buildString {
            append(responseTextEvent("response.reasoning_summary_text.delta", "rs_1", 0, "delta", "先判断"))
            append(responseTextEvent("response.reasoning_summary_text.done", "rs_1", 0, "text", "先判断"))
            append(responseTextEvent("response.output_text.delta", "msg_1", 1, "delta", "我先查一下。"))
            append(responseTextEvent("response.output_text.done", "msg_1", 1, "text", "我先查一下。"))
            append(
                event(
                    "response.output_item.added",
                    JSONObject()
                        .put("output_index", 2)
                        .put("item", JSONObject().put("id", "ws_1").put("type", "web_search_call")),
                ),
            )
            append(
                event(
                    "response.output_item.done",
                    JSONObject()
                        .put("output_index", 2)
                        .put(
                            "item",
                            JSONObject()
                                .put("id", "ws_1")
                                .put("type", "web_search_call")
                                .put("status", "completed"),
                        ),
                ),
            )
            append(responseTextEvent("response.reasoning_summary_text.delta", "rs_2", 3, "delta", "整理结果"))
            append(responseTextEvent("response.reasoning_summary_text.done", "rs_2", 3, "text", "整理结果"))
            append(responseTextEvent("response.output_text.delta", "msg_2", 4, "delta", "这是最终答案。"))
            append(responseTextEvent("response.output_text.done", "msg_2", 4, "text", "这是最终答案。"))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", terminalOutput),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "搜索")),
                    JSONArray(),
                ),
                AgentRunController(),
                events::add,
            )

            assertEquals("我先查一下。这是最终答案。", result.assistantMessage.getString("content"))
            assertEquals(
                listOf(
                    "start:THINKING:0",
                    "delta:THINKING:0:先判断",
                    "end:THINKING:0",
                    "start:TEXT:1",
                    "delta:TEXT:1:我先查一下。",
                    "end:TEXT:1",
                    "hosted-start:ws_1",
                    "hosted-end:ws_1",
                    "start:THINKING:2",
                    "delta:THINKING:2:整理结果",
                    "end:THINKING:2",
                    "start:TEXT:3",
                    "delta:TEXT:3:这是最终答案。",
                    "end:TEXT:3",
                ),
                events.mapNotNull(::timelineLabel),
            )
        }
    }

    @Test
    fun completeUsesStreamedToolCallWhenCompletedOutputIsEmpty() {
        val body = buildString {
            append(
                event(
                    "response.output_item.added",
                    JSONObject()
                        .put("output_index", 0)
                        .put(
                            "item",
                            JSONObject()
                                .put("id", "fc_1")
                                .put("type", "function_call")
                                .put("call_id", "call_1")
                                .put("name", "device_info"),
                        ),
                ),
            )
            append(
                event(
                    "response.function_call_arguments.delta",
                    JSONObject().put("item_id", "fc_1").put("delta", "{\"detail\":true}"),
                ),
            )
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", JSONArray()),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "读取设备")),
                    JSONArray(),
                ),
                AgentRunController(),
            )

            val call = result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("call_1", call.getString("id"))
            assertEquals("device_info", call.getJSONObject("function").getString("name"))
            assertEquals("{\"detail\":true}", call.getJSONObject("function").getString("arguments"))
            assertEquals("tool_calls", result.assistantMessage.getString("finish_reason"))
            assertNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
        }
    }

    @Test
    fun completeRejectsEofWithoutTerminalEvent() {
        withSseServer(event("response.output_text.delta", JSONObject().put("delta", "partial"))) { baseUrl ->
            val thrown = runCatching {
                OpenAiResponsesProvider.complete(
                    ProviderRequest(
                        config(baseUrl),
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                        JSONArray(),
                    ),
                    AgentRunController(),
                )
            }.exceptionOrNull()
            assertTrue(thrown?.message.orEmpty().contains("缺少合法终止事件"))
        }
    }

    @Test
    fun citationsDeduplicateAndFallBackForInvalidOffsets() {
        val formatted = ResponsesCitationFormatter.apply(
            "中文回答",
            listOf(
                CitationAnnotation(0, 2, "https://example.com/a", "A"),
                CitationAnnotation(0, 2, "https://example.com/a", "重复"),
                CitationAnnotation(99, 120, "https://example.com/b", "B"),
            ),
        )
        assertEquals(1, "https://example.com/a".toRegex().findAll(formatted).count())
        assertTrue(formatted.contains("[[1]]"))
        assertTrue(formatted.contains("来源："))
        assertTrue(formatted.contains("https://example.com/b"))
    }

    private fun config(baseUrl: String) = AgentModelClient.ModelConfig(
        providerSourceType = "custom",
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "系统提示",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    private fun event(type: String, fields: JSONObject): String {
        val event = JSONObject(fields.toString()).put("type", type)
        return "event: $type\ndata: $event\n\n"
    }

    private fun responseTextEvent(
        type: String,
        itemId: String,
        outputIndex: Int,
        valueKey: String,
        value: String,
    ): String = event(
        type,
        JSONObject()
            .put("item_id", itemId)
            .put("output_index", outputIndex)
            .put("summary_index", 0)
            .put("content_index", 0)
            .put(valueKey, value),
    )

    private fun reasoningItem(id: String, text: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "reasoning")
        .put("summary", JSONArray().put(JSONObject().put("text", text)))

    private fun messageItem(id: String, text: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "message")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))

    private fun timelineLabel(event: ProviderEvent): String? = when (event) {
        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
        is ProviderEvent.HostedToolStarted -> "hosted-start:${event.id}"
        is ProviderEvent.HostedToolFinished -> "hosted-end:${event.id}"
        else -> null
    }

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        writeBody: ((OutputStream) -> Unit)? = null,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/responses") { exchange ->
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

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }

    private fun JSONArray.types(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("type") }
}
