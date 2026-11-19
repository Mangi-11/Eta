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

    @Test
    fun oversizedSnapshotEncodesWithinWireBudgetAndPreservesTotalsAndSurrogates() {
        val emojiUnit = "🚀🔥"
        val steps = (1..48).map { index ->
            SubagentStepSnapshot(
                id = "tool-1-call_$index",
                kind = if (index % 3 == 0) SubagentStepSnapshot.KIND_THINKING else SubagentStepSnapshot.KIND_TOOL,
                title = "步骤 $index \"引号\" \\ 反斜杠 $emojiUnit",
                detail = ("详情内容$index\\\"\n$emojiUnit").repeat(160),
                status = SubagentStepSnapshot.STATUS_SUCCESS,
                toolName = if (index % 3 == 0) "" else "read_file",
            )
        }
        val expectedToolSteps = steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }
        val usage = SubagentUsageSnapshot(
            inputTokens = 48_000,
            cachedTokens = 36_000,
            cacheWriteTokens = 1_200,
            outputTokens = 2_400,
            reasoningTokens = 600,
            llmMs = 15_200L,
        )
        val huge = SubagentActivitySnapshot(
            subagentId = "sub_oversized",
            subagentType = "coder",
            description = "长任务压力测试 $emojiUnit",
            prompt = ("任务要求 $emojiUnit\n").repeat(600),
            model = "Test Model",
            status = SubagentActivitySnapshot.STATUS_COMPLETED,
            currentAction = "完成 · $expectedToolSteps 次工具调用",
            steps = steps,
            output = ("汇报行 \"quoted\" $emojiUnit\n").repeat(600),
            usage = usage,
        )

        val encoded = huge.encodeForWire()
        assertTrue(
            "encodeForWire length=${encoded.length} must stay within WIRE_BUDGET_CHARS",
            encoded.length <= SubagentActivitySnapshot.WIRE_BUDGET_CHARS,
        )
        assertFalse("UTF-16 surrogate pair must not be split", hasUnpairedSurrogate(encoded))

        val decoded = SubagentActivitySnapshot.decode(encoded)
        assertNotNull(decoded)
        assertEquals(SubagentActivitySnapshot.STATUS_COMPLETED, decoded!!.status)
        assertEquals(usage, decoded.usage)
        assertEquals(expectedToolSteps, decoded.toolStepCount)
        assertTrue("Earlier steps should be recorded as omitted when trimmed", decoded.omittedSteps > 0 || decoded.steps.size == steps.size)
        if (decoded.steps.isNotEmpty()) {
            assertEquals(steps.last().id, decoded.steps.last().id)
        }
    }

    @Test
    fun runtimeWireSanitizesOversizedSubagentSnapshotsWithoutCorruptingJson() {
        val steps = (1..24).map { index ->
            SubagentStepSnapshot(
                id = "tool-$index",
                kind = SubagentStepSnapshot.KIND_TOOL,
                title = "读取文件 $index",
                detail = "x".repeat(1_800),
                status = SubagentStepSnapshot.STATUS_SUCCESS,
                toolName = "read_file",
            )
        }
        val usage = SubagentUsageSnapshot(inputTokens = 20_000, cachedTokens = 15_000, outputTokens = 800, llmMs = 4_200L)
        val rawOversizedJson = SubagentActivitySnapshot(
            subagentId = "sub_wire",
            subagentType = "explore",
            description = "跨进程超限兜底",
            status = SubagentActivitySnapshot.STATUS_COMPLETED,
            steps = steps,
            output = "总结内容".repeat(2_000),
            usage = usage,
        ).encode()
        assertTrue("Raw snapshot must exceed 16_000 chars to exercise wire guard", rawOversizedJson.length > 16_000)

        val progressBundle = AgentRuntimeWire.eventToBundle(
            AgentEvent.SubagentProgress(
                round = 1,
                toolCallId = "call_progress",
                subagentId = "sub_wire",
                subagentType = "explore",
                snapshotJson = rawOversizedJson,
            ),
        )
        val decodedProgress = AgentRuntimeWire.eventFromBundle(progressBundle) as? AgentEvent.SubagentProgress
        val progressSnapshot = SubagentActivitySnapshot.decode(decodedProgress?.snapshotJson)
        assertNotNull(progressSnapshot)
        assertEquals(24, progressSnapshot!!.toolStepCount)
        assertEquals(usage, progressSnapshot.usage)

        val finishedBundle = AgentRuntimeWire.eventToBundle(
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_progress",
                name = AgentSubagentToolCatalog.TOOL_NAME,
                resultSummary = "完成 · 24 次工具调用",
                imageCount = 0,
                imageBytes = 0,
                success = true,
                command = rawOversizedJson,
            ),
        )
        val decodedFinished = AgentRuntimeWire.eventFromBundle(finishedBundle) as? AgentEvent.ToolFinished
        val finishedSnapshot = SubagentActivitySnapshot.decode(decodedFinished?.command)
        assertNotNull(finishedSnapshot)
        assertEquals(24, finishedSnapshot!!.toolStepCount)
        assertEquals(usage, finishedSnapshot.usage)
    }

    @Test
    fun readOnlyPresetsCannotBeEscalatedAndDefaultWildcardExcludesDeviceWriteTools() {
        val escalatedExplore = SubagentProfile.resolve(
            name = "explore",
            customTools = listOf("terminal", "write_file", "*"),
        )
        assertFalse(escalatedExplore.isToolAllowed("terminal"))
        assertFalse(escalatedExplore.isToolAllowed("write_file"))
        assertTrue(escalatedExplore.isToolAllowed("read_file"))

        val narrowedExplore = SubagentProfile.resolve(
            name = "explore",
            customTools = listOf("read_file", "terminal"),
        )
        assertTrue(narrowedExplore.isToolAllowed("read_file"))
        assertFalse(narrowedExplore.isToolAllowed("glob_files"))
        assertFalse(narrowedExplore.isToolAllowed("terminal"))

        val defaultEphemeral = SubagentProfile.resolve(name = "log-auditor")
        assertTrue(defaultEphemeral.isToolAllowed("read_file"))
        assertTrue(defaultEphemeral.isToolAllowed("terminal"))
        assertFalse(defaultEphemeral.isToolAllowed("set_setting"))
        assertFalse(defaultEphemeral.isToolAllowed("set_volume"))
        assertFalse(defaultEphemeral.isToolAllowed("app_state_control"))
        assertFalse(defaultEphemeral.isToolAllowed("observe_screen"))

        val explicitAlarm = SubagentProfile.resolve(
            name = "alarm-helper",
            customTools = listOf("set_alarm", "observe_screen"),
        )
        assertTrue(explicitAlarm.isToolAllowed("set_alarm"))
        assertFalse(explicitAlarm.isToolAllowed("observe_screen"))
    }

    @Test
    fun customProfileYamlQuotesAndBackslashesRoundTripWithoutDuplication() {
        val tempDir = java.nio.file.Files.createTempDirectory("eta-subagent-yaml-test").toFile()
        try {
            val description = "专门解析 \"JSON\" 与路径 C:\\work\\logs: 快速定位"
            val whenToUse = "当需要分析 \"错误\" 或 C:\\temp 下的日志时"
            val first = SubagentProfileCatalog.saveCustomProfile(
                name = "quote-tester",
                description = description,
                whenToUse = whenToUse,
                prompt = "请直接返回结论。",
                agentsDir = tempDir,
            )
            assertEquals(description, first.summary)
            assertEquals(whenToUse, first.whenToUse)

            // 模拟设置页二次编辑保存，确认反斜杠不会翻倍。
            val second = SubagentProfileCatalog.saveCustomProfile(
                name = first.name,
                description = first.summary,
                whenToUse = first.whenToUse,
                prompt = first.roleInstruction,
                agentsDir = tempDir,
            )
            assertEquals(description, second.summary)
            assertEquals(whenToUse, second.whenToUse)
            assertTrue(SubagentProfileCatalog.existsCustomProfile("quote-tester", tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun sessionStoreBoundsHistoryAtUserBoundaryAndClearsByConversation() {
        val key = "conv-subagent-bound-test"
        SubagentSessionStore.clearConversation(key)
        try {
            val entry = SubagentSessionStore.create(key, SubagentProfile.EXPLORE)
            val messages = (1..40).flatMap { index ->
                listOf(
                    AgentModelClient.ConversationMessage(role = "user", content = "q-$index"),
                    AgentModelClient.ConversationMessage(role = "assistant", content = "a-$index"),
                )
            }
            SubagentSessionStore.complete(key, entry.subagentId, messages)
            val stored = SubagentSessionStore.get(key, entry.subagentId)
            assertNotNull(stored)
            assertTrue(stored!!.history.size <= 64)
            assertEquals("user", stored.history.first().role)
            assertEquals("a-40", stored.history.last().content)

            SubagentSessionStore.clearConversation(key)
            assertEquals(null, SubagentSessionStore.get(key, entry.subagentId))
        } finally {
            SubagentSessionStore.clearConversation(key)
        }
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
            } else if (Character.isLowSurrogate(ch)) {
                return true
            } else {
                index += 1
            }
        }
        return false
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(linkedSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
