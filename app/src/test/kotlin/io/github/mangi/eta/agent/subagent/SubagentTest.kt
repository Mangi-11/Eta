package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentLoop
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentTest {
    @Test
    fun builtinProfilesEnforceReadOnlyAndForbidRecursiveOrGuiTools() {
        val fullCatalog = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true),
        ).also { it.put(AgentSubagentToolCatalog.schema()) }

        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
            val allowed = profile.filterTools(fullCatalog).toolNames()
            assertFalse("subagent" in allowed)
            assertFalse("observe_screen" in allowed)
            assertFalse("tap_element" in allowed)
            assertFalse("type_text" in allowed)
            assertFalse("browser_use" in allowed)
            assertFalse("memory_write" in allowed)
            assertTrue("read_file" in allowed)
            assertTrue("glob_files" in allowed)
            assertTrue("grep_files" in allowed)
            assertTrue("web_search" in allowed)
        }

        val exploreTools = SubagentProfile.EXPLORE.filterTools(fullCatalog).toolNames()
        assertFalse("write_file" in exploreTools)
        assertFalse("edit_file" in exploreTools)
        assertFalse("terminal" in exploreTools)

        val planTools = SubagentProfile.PLAN.filterTools(fullCatalog).toolNames()
        assertFalse("write_file" in planTools)
        assertFalse("edit_file" in planTools)
        assertFalse("terminal" in planTools)

        val coderTools = SubagentProfile.CODER.filterTools(fullCatalog).toolNames()
        assertTrue("write_file" in coderTools)
        assertTrue("edit_file" in coderTools)
        assertTrue("terminal" in coderTools)
    }

    @Test
    fun customSubagentProfileCanBeSavedDiscoveredAndFilteredSafely() {
        val tempDir = java.nio.file.Files.createTempDirectory("eta-subagent-test").toFile()
        try {
            val saved = SubagentProfileCatalog.saveCustomProfile(
                name = "bill-analyzer",
                description = "专门检索与汇总个人账单与订单",
                whenToUse = "当用户需要统计账单或查询订单时使用",
                tools = listOf("summarize_bills", "search_personal_orders", "observe_screen", "subagent"),
                prompt = "你是专门负责账单分析的 Subagent。",
                agentsDir = tempDir,
            )
            assertFalse(saved.builtin)
            assertEquals("bill-analyzer", saved.name)
            assertTrue(saved.isToolAllowed("summarize_bills"))
            assertTrue(saved.isToolAllowed("search_personal_orders"))
            assertFalse(saved.isToolAllowed("observe_screen"))
            assertFalse(saved.isToolAllowed("subagent"))
            assertFalse(saved.isToolAllowed("subagent_profile"))
            assertFalse(saved.isToolAllowed("write_file"))

            val all = SubagentProfileCatalog.listAll(tempDir)
            assertTrue(all.any { it.name == "bill-analyzer" && !it.builtin })
            val schemaText = AgentSubagentToolCatalog.schema(all).toString()
            assertTrue(schemaText.contains("bill-analyzer"))

            assertTrue(SubagentProfileCatalog.deleteCustomProfile("bill-analyzer", tempDir))
            assertFalse(SubagentProfileCatalog.listAll(tempDir).any { it.name == "bill-analyzer" })

            // 未保存在磁盘的任意 subagent_type 可直接当场生成临时专职 Subagent 运行，不落盘
            val ephemeral = SubagentProfile.resolve(
                name = "ad-hoc-auditor",
                catalog = SubagentProfileCatalog.listAll(tempDir),
                customRole = "专门核对近三天通知与日程冲突",
                customTools = listOf("search_calendar_events", "recent_notifications", "observe_screen"),
            )
            assertEquals("ad-hoc-auditor", ephemeral.name)
            assertTrue(ephemeral.isToolAllowed("search_calendar_events"))
            assertTrue(ephemeral.isToolAllowed("recent_notifications"))
            assertFalse(ephemeral.isToolAllowed("observe_screen"))
            assertFalse(ephemeral.isToolAllowed("write_file"))
            assertFalse(SubagentProfileCatalog.listAll(tempDir).any { it.name == "ad-hoc-auditor" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun agentLoopExecutesMultipleSubagentCallsInParallelAndPreservesOrder() {
        val startedLatch = CountDownLatch(2)
        val releaseLatch = CountDownLatch(1)
        val events = mutableListOf<AgentEvent>()

        val provider = object : AgentProviderClient {
            override val id: String = "fake-provider"
            override val capabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = true,
                streamingToolCalls = true,
                imageInput = false,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = true,
            )
            private var callCount = 0
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                callCount += 1
                if (callCount == 1) {
                    val toolCalls = JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "subagent")
                                        .put(
                                            "arguments",
                                            JSONObject()
                                                .put("subagent_type", "explore")
                                                .put("description", "检索日历")
                                                .put("prompt", "查找本周日历")
                                                .toString(),
                                        ),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("id", "call_2")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "subagent")
                                        .put(
                                            "arguments",
                                            JSONObject()
                                                .put("subagent_type", "coder")
                                                .put("description", "检查日志")
                                                .put("prompt", "分析错误日志")
                                                .toString(),
                                        ),
                                ),
                        )
                    return ProviderResponse(
                        assistantMessage = JSONObject()
                            .put("role", "assistant")
                            .put("content", "")
                            .put("finish_reason", "tool_calls")
                            .put("tool_calls", toolCalls),
                    )
                }
                return ProviderResponse(
                    assistantMessage = JSONObject()
                        .put("role", "assistant")
                        .put("content", "汇总完成")
                        .put("finish_reason", "stop"),
                )
            }
        }

        val tools = JSONArray().put(AgentSubagentToolCatalog.schema())
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "开始"))
        val loop = AgentLoop(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                startedLatch.countDown()
                // 只有两个调用真正并发进入时，startedLatch 才能归零。
                assertTrue(
                    "Multiple subagent tool calls in the same turn must run concurrently",
                    startedLatch.await(3, TimeUnit.SECONDS),
                )
                releaseLatch.countDown()
                val snapshot = SubagentActivitySnapshot(
                    subagentId = "sub_${call.id}",
                    subagentType = "explore",
                    description = call.id,
                    status = SubagentActivitySnapshot.STATUS_COMPLETED,
                    output = "result-${call.id}",
                ).encode()
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool_calls", 2)
                        .put("summary", "result-${call.id}")
                        .toString(),
                    command = snapshot,
                )
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = { synchronized(events) { events += it } },
        )

        val result = loop.run()
        assertEquals("汇总完成", result.content)
        val toolMessages = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.optString("role") == "tool" }
        assertEquals(listOf("call_1", "call_2"), toolMessages.map { it.optString("tool_call_id") })

        val finishedEvents = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(2, finishedEvents.size)
        finishedEvents.forEach { finished ->
            assertNotNull(SubagentActivitySnapshot.decode(finished.command))
        }
    }

    @Test
    fun subagentProgressEventRoundTripsThroughRuntimeWire() {
        val snapshot = SubagentActivitySnapshot(
            subagentId = "sub_1",
            subagentType = "explore",
            description = "检索近期便签",
            prompt = "查找最近 7 天的便签记录",
            model = "GPT-5.4 Mini",
            status = SubagentActivitySnapshot.STATUS_RUNNING,
            currentAction = "正在调用 search_notes",
            steps = listOf(
                SubagentStepSnapshot(
                    id = "tool-1-tc1",
                    kind = SubagentStepSnapshot.KIND_TOOL,
                    title = "查询便签",
                    detail = "",
                    status = SubagentStepSnapshot.STATUS_RUNNING,
                    toolName = "search_notes",
                ),
            ),
        )
        val event = AgentEvent.SubagentProgress(
            round = 2,
            toolCallId = "call_99",
            subagentId = "sub_1",
            subagentType = "explore",
            snapshotJson = snapshot.encode(),
        )
        val bundle = AgentRuntimeWire.eventToBundle(event)
        val decoded = AgentRuntimeWire.eventFromBundle(bundle) as? AgentEvent.SubagentProgress
        assertEquals(event, decoded)
        assertEquals(snapshot, SubagentActivitySnapshot.decode(decoded?.snapshotJson))
    }

    @Test
    fun subagentUsageSnapshotRoundTripsInsideActivitySnapshot() {
        val snapshot = SubagentActivitySnapshot(
            subagentId = "sub_2",
            subagentType = "explore",
            description = "统计",
            status = SubagentActivitySnapshot.STATUS_COMPLETED,
            usage = SubagentUsageSnapshot(
                inputTokens = 12_000,
                cachedTokens = 9_600,
                cacheWriteTokens = 800,
                outputTokens = 450,
                reasoningTokens = 120,
                llmMs = 2_400L,
            ),
        )
        val decoded = SubagentActivitySnapshot.decode(snapshot.encode())
        assertEquals(snapshot, decoded)
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(linkedSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
