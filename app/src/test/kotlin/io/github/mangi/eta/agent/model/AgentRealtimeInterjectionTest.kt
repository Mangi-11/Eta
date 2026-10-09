package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient.ModelResponse
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRealtimeInterjectionTest {
    @Test
    fun interjectionAbortsStalledHttpForAllProvidersWithoutCancellingRunResources() {
        for (protocol in listOf("chat", "responses", "anthropic")) verifyInterjection(protocol)
    }

    @Test
    fun interruptedStreamDiscardsPartialReasoningTextAndToolArgumentsBeforeRedirecting() =
        verifyInterjection("chat", partialStream = true)

    private fun verifyInterjection(protocol: String, partialStream: Boolean = false) {
        val controller = AgentRunController()
        val received = CountDownLatch(1)
        val streamed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val closedTools = AtomicInteger()
        val executedTools = AtomicInteger()
        val answer = AtomicReference<ModelResponse.Text?>()
        val failure = AtomicReference<Throwable?>()
        controller.register { closedTools.incrementAndGet() }
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                if (requests.size == 1) {
                    if (partialStream) {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        val delta = JSONObject().put("reasoning_content", "unfinished-reasoning")
                            .put("content", "unfinished-text").put("tool_calls", JSONArray().put(
                                JSONObject().put("index", 0).put("id", "unfinished-call").put("type", "function")
                                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{")),
                            ))
                        exchange.responseBody.write(chatChunk(delta).toByteArray())
                        exchange.responseBody.flush()
                    }
                    received.countDown()
                    release.await(10, TimeUnit.SECONDS)
                } else {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(finalResponse(protocol).toByteArray())
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val worker = thread(name = "interjection-http-$protocol", isDaemon = true) {
            try {
                val config = AgentModelClient.ModelConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "fixture", model = "fixture",
                    systemPrompt = "", providerType = if (protocol == "anthropic") ProviderTypes.ANTHROPIC else ProviderTypes.OPENAI_COMPATIBLE,
                    openAiEndpointMode = if (protocol == "responses") OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS,
                    autoCompactionEnabled = false,
                )
                answer.set(AgentModelClient.complete(config, "original", AgentModelClient.ToolExecutor {
                    executedTools.incrementAndGet()
                    AgentModelClient.ToolResult("{\"ok\":true}")
                }, runController = controller, onEvent = { event ->
                    events += event
                    if (event is AgentEvent.AssistantBlockDelta) streamed.countDown()
                }))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(protocol, received.await(5, TimeUnit.SECONDS))
            if (partialStream) assertTrue(streamed.await(5, TimeUnit.SECONDS))
            assertTrue(controller.steer("redirect now"))
            assertTrue("$protocol did not restart a stalled request promptly", finished.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(protocol, it) }
            assertEquals("redirected", answer.get()?.content)
            assertEquals(2, requests.size)
            assertTrue(requests[1].contains("redirect now"))
            assertFalse(requests[1].contains("unfinished-"))
            assertFalse(answer.get()!!.transcript.toString().contains("unfinished-"))
            assertTrue(events.any { it is AgentEvent.ModelRequestInterrupted })
            assertFalse(events.any { it is AgentEvent.RunFailed || it is AgentEvent.ModelRetryScheduled })
            assertEquals(0, executedTools.get())
            assertEquals(0, closedTools.get())
            assertFalse(controller.isCancelled)
        } finally {
            release.countDown()
            controller.cancel()
            worker.join(2_000)
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun chatChunk(delta: JSONObject, finish: String? = null) =
        "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))}\n\n"

    private fun event(type: String, data: JSONObject) = "event: $type\ndata: ${data.put("type", type)}\n\n"

    private fun finalResponse(protocol: String): String = when (protocol) {
        "chat" -> chatChunk(JSONObject().put("content", "redirected"), "stop") + "data: [DONE]\n\n"
        "responses" -> event("response.completed", JSONObject().put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "message")
                .put("id", "msg-2").put("role", "assistant").put("content", JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "redirected"),
                ))))))
        else -> event("message_start", JSONObject().put("message", JSONObject().put("id", "msg-2")
            .put("role", "assistant").put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 1)))) +
            event("content_block_start", JSONObject().put("index", 0).put("content_block", JSONObject().put("type", "text").put("text", ""))) +
            event("content_block_delta", JSONObject().put("index", 0).put("delta", JSONObject().put("type", "text_delta").put("text", "redirected"))) +
            event("content_block_stop", JSONObject().put("index", 0)) +
            event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn"))) +
            event("message_stop", JSONObject())
    }
}
