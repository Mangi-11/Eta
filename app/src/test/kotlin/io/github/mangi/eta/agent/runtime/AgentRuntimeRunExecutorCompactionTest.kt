package io.github.mangi.eta.agent.runtime

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeRunExecutorCompactionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        SettingsDataStore.init(context)
        AgentMemoryRepository.init(context)
        McpServerRepository.init(context)
        ProviderRepository.init(context)
        TerminalRuntime.initialize(context)
    }

    @After
    fun tearDown() {
        AgentRunCheckpointStore.remove(context, "run-chat")
        AgentRunCheckpointStore.remove(context, "run-compact")
        EtaDatabase.closeForTests()
    }

    @Test
    fun executorSendsIdenticalToolCatalogForChatAndManualCompact() {
        val capturedBodies = CopyOnWriteArrayList<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverExecutor = Executors.newSingleThreadExecutor()
        server.executor = serverExecutor
        server.createContext("/chat/completions") { exchange ->
            val requestBody = JSONObject(
                exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) },
            )
            capturedBodies += requestBody
            val replyText = if (capturedBodies.size == 1) "正常回答。" else "精简摘要。"
            val sse = sseChunk(JSONObject().put("content", replyText), finishReason = "stop") +
                usageChunk(JSONObject().put("prompt_tokens", 1200).put("completion_tokens", 20)) +
                "data: [DONE]\n\n"
            val bytes = sse.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val config = AgentModelClient.ModelConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "fixture",
                model = "fixture",
                systemPrompt = "固定规则",
                terminalTools = false,
                browserTools = false,
                deviceDirectTools = false,
                deviceSensitiveReadTools = false,
                deviceSensitiveActionTools = false,
                subagentTools = true,
                contextWindow = 128_000,
            )
            val handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff-compact-cache",
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = AgentUiHandoffPayload(conversationId = "conv-compact-cache").toJson(),
            )
            val history = (1..4).flatMap { turn ->
                listOf(
                    AgentModelClient.ConversationMessage("user", "第 $turn 轮提问"),
                    AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(800)),
                )
            }
            val runtimeExecutor = AgentRuntimeRunExecutor(
                context = context,
                currentPermissions = {
                    AgentRuntimePolicy.Permissions(
                        terminalTools = false,
                        browserTools = false,
                        subagentTools = true,
                    )
                },
                snapshotRequest = { it },
                guiControl = GuiControlCoordinator(),
                onAcceptedEvent = { _, _ -> },
                persistArtifacts = { _, _, _ -> },
            )

            val chatRequest = AgentRuntimeWire.RunRequest(
                runId = "run-chat",
                prompt = "继续",
                config = config,
                images = emptyList(),
                history = history,
                handoff = handoff,
                operation = AgentRuntimeWire.OP_CHAT,
            )
            val chatOutcome = runtimeExecutor.execute(
                session = AgentRuntimeSession(chatRequest.runId, operation = chatRequest.operation),
                request = chatRequest,
            )
            assertTrue(chatOutcome.result.error.orEmpty(), chatOutcome.result.ok)

            val compactRequest = AgentRuntimeWire.RunRequest(
                runId = "run-compact",
                prompt = "",
                config = config,
                images = emptyList(),
                history = history + listOf(
                    AgentModelClient.ConversationMessage("user", "继续"),
                    AgentModelClient.ConversationMessage("assistant", "正常回答。"),
                ),
                handoff = handoff,
                operation = AgentRuntimeWire.OP_COMPACT,
            )
            val compactOutcome = runtimeExecutor.execute(
                session = AgentRuntimeSession(compactRequest.runId, operation = compactRequest.operation),
                request = compactRequest,
            )
            assertTrue(compactOutcome.result.error.orEmpty(), compactOutcome.result.ok)

            assertEquals(2, capturedBodies.size)
            val chatBody = capturedBodies[0]
            val compactBody = capturedBodies[1]
            val chatTools = chatBody.getJSONArray("tools")
            val compactTools = compactBody.getJSONArray("tools")
            val compactToolNames = (0 until compactTools.length()).map { index ->
                compactTools.getJSONObject(index).getJSONObject("function").getString("name")
            }

            assertTrue(AgentConversationToolCatalog.READ_HISTORY in compactToolNames)
            assertEquals(chatTools.length(), compactTools.length())
            assertEquals(chatTools.toString(), compactTools.toString())
            assertEquals("none", compactBody.getString("tool_choice"))
        } finally {
            server.stop(0)
            serverExecutor.shutdownNow()
        }
    }

    private fun sseChunk(delta: JSONObject, finishReason: String? = null): String {
        val choice = JSONObject()
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }

    private fun usageChunk(usage: JSONObject): String =
        "data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n"
}
