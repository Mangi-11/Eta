package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import java.util.concurrent.atomic.AtomicInteger
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.model.catalog.AgentToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentToolSchema
import io.github.mangi.eta.agent.model.format.AgentTraceFormatter
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentModelExecutionException
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.AgentModelRetry
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.AnthropicEphemeralState
import io.github.mangi.eta.agent.model.provider.AssistantBlockKind
import io.github.mangi.eta.agent.model.provider.EndpointKind
import io.github.mangi.eta.agent.model.provider.ProviderCapabilities
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderResponse
import io.github.mangi.eta.agent.model.provider.openai.OpenAiRequestMessages
import io.github.mangi.eta.agent.model.provider.openai.ResponsesRequestBuilder

class AgentModelClientLoopTest {
    @Test
    fun rootProbeStatesKeepThePrefixStableUntilGrantActuallyChanges() {
        var root = RootAccessState(isChecking = true)
        val calls = mutableListOf<String>()
        val systems = mutableListOf<String>()
        val toolLists = mutableListOf<String>()
        fun record(request: ProviderRequest) {
            systems += (0 until request.messages.length()).map(request.messages::getJSONObject)
                .filter { it.optString("role") == "system" }.joinToString { it.toString() }
            toolLists += request.tools.toString()
        }
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                record(request)
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("context", "get_current_context", "{}")))
            },
            { request, _ ->
                record(request)
                assertFalse(request.tools.toString().contains("set_setting"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("probe", "terminal", """{"action":"check_root"}""")))
            },
            { request, _ ->
                record(request)
                assertTrue(request.messages.toString().contains("Root 授权已确认"))
                assertTrue(request.tools.toString().contains("set_setting"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("root-command", "terminal", """{"command":"id -u","identity":"root"}""")))
            },
            { _, _ -> assistant(content = "已验证 Root", finishReason = "stop") },
        ))
        AgentModelClient.complete(
            modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true), "检查 Root",
            AgentModelClient.ToolExecutor { call ->
                calls += call.id
                root = if (call.id == "context") RootAccessState(RootAccessStatus.TIMED_OUT, suPresent = true)
                    else RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = root.isGranted, rootState = root) },
        )
        assertEquals(listOf("context", "probe", "root-command"), calls)
        // 检测中 → 超时都未授权：系统提示与工具目录逐字节不变；授权确认后才整体更新。
        assertEquals(systems[0], systems[1])
        assertEquals(toolLists[0], toolLists[1])
        assertNotEquals(systems[1], systems[2])
    }

    @Test
    fun transientCapabilityLossDoesNotShrinkTheToolCatalogMidRun() {
        var accessibility = true
        val toolLists = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                toolLists += request.tools.toString()
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("context", "get_current_context", "{}")))
            },
            { request, _ ->
                toolLists += request.tools.toString()
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            modelConfig(), "看一下屏幕",
            AgentModelClient.ToolExecutor {
                accessibility = false
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false, accessibilityAvailable = accessibility) },
        )
        assertTrue(toolLists[0].contains("observe_screen"))
        assertEquals(toolLists[0], toolLists[1])
    }

    @Test
    fun capabilityLostMidRunStaysListedButExecutionReportsTheCatalogCode() {
        var notifications = true
        val executed = mutableListOf<String>()
        val results = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("recent_notifications"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("ctx", "get_current_context", "{}")))
            },
            { request, _ ->
                assertTrue(request.tools.toString().contains("recent_notifications"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("notes", "recent_notifications", "{}")))
            },
            { request, _ ->
                results += request.messages.toString()
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            modelConfig().copy(deviceSensitiveReadTools = true), "看看通知",
            AgentModelClient.ToolExecutor { call ->
                executed += call.id
                notifications = false
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false, notificationsAllowed = notifications) },
        )
        assertEquals(listOf("ctx"), executed)
        assertTrue(results.single().contains("NOTIFICATION_ACCESS_REQUIRED"))
    }

    @Test
    fun hostedSearchReplacesOnlyTheLocalSearchInSupportedConfigurations() {
        for (providerType in listOf(ProviderTypes.OPENAI_COMPATIBLE, ProviderTypes.ANTHROPIC)) {
            for (endpoint in listOf(OpenAiEndpointMode.RESPONSES, OpenAiEndpointMode.CHAT_COMPLETIONS)) {
                for (hosted in listOf(false, true)) for (browser in listOf(false, true)) {
                    val effectiveHosted = providerType == ProviderTypes.OPENAI_COMPATIBLE &&
                        endpoint == OpenAiEndpointMode.RESPONSES && hosted
                    val config = modelConfig().copy(
                        providerType = providerType, openAiEndpointMode = endpoint,
                        hostedWebSearchEnabled = hosted, browserTools = browser,
                    )
                    val provider = ScriptedProvider(listOf({ request, _ ->
                        val names = (0 until request.tools.length()).map {
                            request.tools.getJSONObject(it).getJSONObject("function").getString("name")
                        }
                        assertEquals(browser && !effectiveHosted, "web_search" in names)
                        assertEquals(browser, "fetch_url" in names)
                        assertEquals(browser, "browser_use" in names)
                        assertEquals(effectiveHosted, request.messages.toString().contains("Provider 托管的 web_search"))
                        assertEquals(browser && !effectiveHosted, request.messages.toString().contains("使用本地 web_search"))
                        if (effectiveHosted) {
                            val outgoing = ResponsesRequestBuilder.build(config, request.messages, request.tools).getJSONArray("tools")
                            assertEquals(1, (0 until outgoing.length()).count { outgoing.getJSONObject(it).optString("type") == "web_search" })
                            assertFalse((0 until outgoing.length()).any { outgoing.getJSONObject(it).optString("name") == "web_search" })
                        }
                        assistant(content = "完成", finishReason = "stop")
                    }))
                    AgentModelClient.complete(config, "查询公开信息", AgentModelClient.ToolExecutor { error("不应执行本地请求") }, provider = provider)
                }
            }
        }
    }

    @Test
    fun hiddenLocalSearchCannotBeExecutedAsAFunctionDuringHostedSearch() {
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("local-search", "web_search", """{"query":"example"}"""))) },
            { request, _ ->
                assertTrue(request.messages.toString().contains("TOOL_NOT_AVAILABLE"))
                assistant(content = "已使用可用能力", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES, hostedWebSearchEnabled = true, browserTools = true),
            prompt = "查询公开信息", provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("托管搜索不应误入本地执行器") },
        )
    }

    @Test
    fun eachRoundUsesOneCapabilitySnapshotForToolAvailabilityAndPrompt() {
        var root = true
        var captures = 0
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("first", "get_current_context", "{}")))
            },
            { request, _ ->
                assertFalse(request.tools.toString().contains("set_setting"))
                assertFalse(request.messages.toString().contains("相关应用私有文件与数据库"))
                assertTrue(request.messages.toString().contains("identity=user"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("stale", "set_setting", "{}"),
                ))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("TOOL_NOT_AVAILABLE"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "开始",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                root = false
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("first"), executed)
        // 每轮一次目录快照，外加每个实际执行的目录工具一次执行前复核。
        assertEquals(5, captures)
    }

    @Test
    fun signedAnthropicToolRoundKeepsTheOriginalToolAndSystemDeclarations() {
        var root = true
        var captures = 0
        val firstAssistant = assistant(
            finishReason = "tool_calls",
            toolCalls = listOf(toolCall("signed-call", "get_current_context", "{}")),
        ).also { response ->
            AnthropicEphemeralState.attachContentBlocks(response, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "")
                    .put("signature", "signed-prefix"))
                .put(JSONObject().put("type", "tool_use").put("id", "signed-call")
                    .put("name", "get_current_context").put("input", JSONObject())))
        }
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                firstAssistant
            },
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "开始",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                root = false
                AgentModelClient.ToolResult("工具结果")
            },
        )

        // 签名回合沿用上一轮的目录不再取快照；另一次来自工具执行前的可用性复核。
        assertEquals(3, captures)
    }

    @Test
    fun textOnlyRunReturnsIncrementalTranscript() {
        val provider = ScriptedProvider(
            assistant(content = "完成", finishReason = "stop")
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "当前问题",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
        )

        assertEquals("完成", result.content)
        assertEquals(listOf("assistant"), result.transcript.map { it.role })
        assertEquals("完成", result.transcript.single().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolBatchFeedsResultsBackInSourceOrder() {
        val provider = ScriptedProvider(
            assistant(
                content = "先执行",
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
                reasoning = "需要两个结果",
            ),
            assistant(content = "已完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("call", call.id)
                        .toString()
                )
            },
            provider = provider,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals("需要两个结果", result.reasoningContent)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals(
            listOf("assistant", "tool", "tool"),
            provider.requests[1].roleSuffix(3),
        )
        assertEquals("call-1", provider.requests[1].getJSONObjectFromEnd(2).getString("tool_call_id"))
        assertEquals("call-2", provider.requests[1].getJSONObjectFromEnd(1).getString("tool_call_id"))
    }

    @Test
    fun steeringWaitsForWholeToolBatchWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancelledResources = AtomicInteger(0)
        controller.register { cancelledResources.incrementAndGet() }
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "已按补充完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "call-1") controller.steer("改用第二种方案")
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            provider = provider,
            runController = controller,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals(0, cancelledResources.get())
        assertFalse(controller.hasPendingSteering)
        assertEquals("已按补充完成", result.content)
        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("改用第二种方案")
        )
    }

    @Test
    fun steeringAfterTextResponsePreservesThatAssistantTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("再补充一项")
                    assistant(content = "第一段回答", finishReason = "stop")
                },
                { _, _ -> assistant(content = "最终回答", finishReason = "stop") },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        assertEquals("最终回答", result.content)
        assertEquals(
            listOf("assistant", "user", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals("第一段回答", result.transcript.first().content)
    }

    @Test
    fun truncatedToolCallIsReportedWithoutExecution() {
        listOf("length", "max_tokens").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "terminal", "{\"command\":\"rm -")),
                ),
                assistant(content = "已重新规划", finishReason = "stop"),
            )
            var executed = false
            val events = mutableListOf<AgentEvent>()

            val result = AgentModelClient.complete(
                config = modelConfig(),
                prompt = "执行任务",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
                onEvent = events::add,
            )

            assertFalse(executed)
            assertEquals("已重新规划", result.content)
            val toolResult = provider.requests[1].getJSONObjectFromEnd(1)
            assertEquals("tool", toolResult.getString("role"))
            assertTrue(toolResult.getString("content").contains("TRUNCATED_TOOL_CALL"))
            val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
            assertFalse(toolStarted.argsPreview.contains("rm -"))
        }
    }

    @Test
    fun malformedAndDuplicateToolCallsReceiveStableTerminalResults() {
        val malformedCalls = JSONArray()
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put("not-an-object")
        val firstResponse = assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", malformedCalls)
        val provider = ScriptedProvider(
            firstResponse,
            assistant(content = "recovered", finishReason = "stop"),
        )
        val executed = mutableListOf<Pair<String, String>>()

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id to call.name
                AgentModelClient.ToolResult(JSONObject().put("ok", false).toString())
            },
            provider = provider,
        )

        assertEquals(
            listOf(
                "duplicate" to "get_current_context",
                "duplicate_1" to "get_current_context",
            ),
            executed,
        )
        val secondRequest = provider.requests[1]
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            secondRequest
                .getJSONObject(secondRequest.length() - 4)
                .getJSONArray("tool_calls")
                .let { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } },
        )
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            (3 downTo 1).map { offset ->
                secondRequest.getJSONObjectFromEnd(offset).getString("tool_call_id")
            },
        )
    }

    @Test
    fun imageObservationsFollowEveryToolResultInTheBatch() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "observe_screen", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "看到了", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.id == "call-1") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = "data:image/png;base64,AA==",
                                mimeType = "image/png",
                                bytes = 1,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertFalse(result.transcript.any { it.contentJson.contains("base64") })
        assertFalse(result.transcript.any { it.contentJson.contains("未写入持久会话") })
    }

    @Test
    fun toolScreenshotIsConsumedByExactlyOneModelRequest() {
        val screenshot = "data:image/png;base64,c2NyZWVu"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("tap", "tap", "{\"x\":10,\"y\":20}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察后点击",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.name == "observe_screen") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = screenshot,
                                mimeType = "image/png",
                                bytes = 6,
                                source = "screen",
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertFalse(provider.requests[0].toString().contains(screenshot))
        assertTrue(provider.requests[1].toString().contains(screenshot))
        assertFalse(provider.requests[2].toString().contains(screenshot))
        assertFalse(result.transcript.any { it.contentJson.contains(screenshot) })
    }

    @Test
    fun newerToolImageReplacesThePreviousTransientObservation() {
        val firstImage = "data:image/png;base64,Zmlyc3Q="
        val secondImage = "data:image/png;base64,c2Vjb25k"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-1", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-2", "observe_screen", "{}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )
        var observationIndex = 0

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "连续观察",
            toolExecutor = AgentModelClient.ToolExecutor {
                val reference = if (observationIndex++ == 0) firstImage else secondImage
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = listOf(
                        AgentModelClient.ModelImage(
                            reference = reference,
                            mimeType = "image/png",
                            bytes = 6,
                            source = "screen",
                        )
                    ),
                )
            },
            provider = provider,
        )

        assertTrue(provider.requests[1].toString().contains(firstImage))
        assertFalse(provider.requests[2].toString().contains(firstImage))
        assertTrue(provider.requests[2].toString().contains(secondImage))
    }

    @Test
    fun toolArgumentsReachExecutorWithoutSchemaPreflight() {
        val calls = listOf(
            toolCall("write", "write_file", """{"path":"note.md","content":"内容","identity":"USER","extra":true}"""),
            toolCall("read", "read_file", """{"path":"note.md","start_line":1,"offset_bytes":0,"max_bytes":1000000}"""),
            toolCall("missing", "tap", "{}"),
        )
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = calls),
            assistant(content = "完成", finishReason = "stop"),
        )
        val executed = mutableListOf<AgentModelClient.ToolCall>()
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true),
            prompt = "执行工具",
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it
                AgentModelClient.ToolResult(
                    if (it.id == "missing") """{"ok":false,"code":"INVALID_ARGUMENT","message":"缺少坐标"}"""
                    else """{"ok":true}""",
                )
            },
            provider = provider,
        )
        assertEquals(calls.map { call ->
            val function = call.getJSONObject("function")
            AgentModelClient.ToolCall(call.getString("id"), function.getString("name"), function.getString("arguments"))
        }, executed)
        val messages = provider.requests[1].toString()
        assertTrue(messages.contains("缺少坐标"))
        assertFalse(messages.contains("INVALID_TOOL_ARGUMENTS"))
    }

    @Test
    fun remoteToolSchemasAreNotInterpretedByTheLoop() {
        val call = toolCall("remote", "mcp_test_write", """{"content":"正文","extra":true}""")
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = listOf(call)),
            assistant(content = "完成", finishReason = "stop"),
        )
        val executed = mutableListOf<AgentModelClient.ToolCall>()
        AgentModelClient.complete(
            config = modelConfig(), prompt = "写文件", provider = provider,
            additionalTools = JSONArray().put(AgentToolSchema.function("mcp_test_write", "远端文件工具",
                JSONObject("""{"type":"object","required":["mode"],"additionalProperties":false}"""))),
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )
        assertEquals(listOf(AgentModelClient.ToolCall("remote", "mcp_test_write", """{"content":"正文","extra":true}""")), executed)
    }

    @Test
    fun contradictoryStopReasonNeverExecutesToolCalls() {
        listOf("stop").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ),
                assistant(content = "已安全结束", finishReason = "stop"),
            )
            var executed = false

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
            )

            assertFalse(executed)
            assertTrue(
                provider.requests[1]
                    .getJSONObjectFromEnd(1)
                    .getString("content")
                    .contains("UNEXPECTED_TOOL_CALL")
            )
        }
    }

    @Test
    fun refusalEndsRunWithoutReplayingPartialAssistantOrExecutingTools() {
        listOf("content_filter", "refusal").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    content = "半截回复",
                    reasoning = "半截思考",
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ).put(
                    "stop_details",
                    JSONObject().put("type", "refusal").put("category", "cyber").put("explanation", "服务商说明文本"),
                ),
                assistant(content = "不应再请求", finishReason = "stop"),
            )
            var executed = false

            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(
                    config = modelConfig(),
                    prompt = "开始",
                    toolExecutor = AgentModelClient.ToolExecutor {
                        executed = true
                        AgentModelClient.ToolResult("unexpected")
                    },
                    provider = provider,
                )
            }

            assertFalse(executed)
            assertEquals(1, provider.requests.size)
            val cause = failure.cause as AgentModelFailure
            assertEquals("MODEL_CONTENT_FILTER", cause.code)
            assertFalse(cause.retryable)
            assertTrue(cause.message.orEmpty().contains("服务商说明文本"))
            assertTrue(failure.transcript.none { it.role == "assistant" })
        }
    }

    @Test
    fun normalToolStopAliasesExecuteValidatedCalls() {
        listOf("tool_calls", "tool_use").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                ),
                assistant(content = "完成", finishReason = "stop"),
            )
            var executions = 0

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executions += 1
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )

            assertEquals(1, executions)
        }
    }

    @Test
    fun providerFailureCarriesCompletedToolTranscriptForSafeRecovery() {
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        reasoning = "先检查状态",
                        toolCalls = listOf(
                            toolCall("call-1", "get_current_context", "{}")
                        ),
                    )
                },
                { _, _ -> error("provider disconnected") },
            )
        )

        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )
        }

        assertEquals(listOf("assistant", "tool"), failure.transcript.map { it.role })
        assertEquals("先检查状态", failure.reasoningContent)
    }

    @Test
    fun loopContinuesPastFormerLocalLimitsUntilProviderFinishes() {
        val toolRounds = 257
        val responses = List<(ProviderRequest, AgentRunController) -> JSONObject>(toolRounds) { index ->
            { _, _ ->
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")),
                )
            }
        } + listOf<(ProviderRequest, AgentRunController) -> JSONObject>(
            { _, _ -> assistant(content = "完成", finishReason = "stop") }
        )
        val provider = ScriptedProvider(responses)
        var executions = 0
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions += 1
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        ).run()

        assertEquals("完成", result.content)
        assertEquals(toolRounds, executions)
        assertEquals(toolRounds + 1, provider.requests.size)
    }

    @Test
    fun retryPreservesToolResultsAndImagesWithoutReplayingToolsOrFailedReasoning() {
        val requests = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var executions = 0
        val provider = object : AgentProviderClient by ScriptedProvider(emptyList()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request.messages.toString()
                sessions += request.sessionId
                onEvent(ProviderEvent.RequestStarted)
                return when (requests.size) {
                    1 -> ProviderResponse(assistant(
                        finishReason = "tool_calls",
                        reasoning = "先观察",
                        toolCalls = listOf(toolCall("observe-1", "get_current_context", "{}")),
                    ))
                    2 -> {
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败的思考"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "半截回答"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 2, "半截参数"))
                        throw java.net.SocketTimeoutException("timeout")
                    }
                    else -> ProviderResponse(assistant(content = "完成", finishReason = "stop", reasoning = "观察成功"))
                }
            }
        }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))
        val loop = AgentLoop(
            config = modelConfig(), messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions++
                AgentModelClient.ToolResult(
                    content = "观察结果",
                    images = listOf(AgentModelClient.ModelImage("data:image/png;base64,dGVzdA==", "image/png", 4)),
                )
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
            sessionId = "conversation-retry",
        )
        val result = loop.run()
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(List(3) { "conversation-retry" }, sessions)
        assertEquals(requests[1], requests[2])
        assertTrue(requests[2].contains("data:image/png"))
        assertFalse(messages.toString().contains("data:image/png"))
        assertFalse(messages.toString().contains("半截"))
        assertEquals("先观察观察成功", result.reasoningContent)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().round)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } }
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            val response = responses.getOrNull(index)
                ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            contextWindow = 128_000,
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private fun JSONArray.roleSuffix(count: Int): List<String> =
        ((length() - count) until length()).map { index ->
            getJSONObject(index).getString("role")
        }

    private fun JSONArray.getJSONObjectFromEnd(offset: Int): JSONObject =
        getJSONObject(length() - offset)

    @Test
    fun steeringMarksInternalFlagAndOpenAiProjectionStripsIt() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("继续核对日志")
                    assistant(content = "第一阶段", finishReason = "stop")
                },
                { _, _ -> assistant(content = "已完成", finishReason = "stop") },
            ),
        )

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        val steered = provider.requests[1].getJSONObjectFromEnd(1)
        assertEquals("user", steered.getString("role"))
        assertTrue(steered.optBoolean("_eta_steering"))
        val projected = OpenAiRequestMessages.forChatCompletions(provider.requests[1])
        assertFalse(projected.toString().contains("_eta_steering"))
    }

    @Test
    fun steeringDuringScreenshotObservationStaysBeforeTransientImageForPrefixCache() {
        val controller = AgentRunController()
        val screenshot = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,iVBORw0KGgo=",
            mimeType = "image/png",
            bytes = 8,
            width = 1,
            height = 1,
            source = "screen",
            preserveOriginal = true,
        )
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("obs_1", "observe_screen", """{"include_screenshot":true}""")),
                    )
                },
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("tap_1", "tap", """{"x":100,"y":200,"coordinate_space":"normalized"}""")),
                    )
                },
                { _, _ -> assistant(content = "操作完成", finishReason = "stop") },
            ),
        )

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我看屏幕",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == "observe_screen") {
                    controller.steer("顺便点一下确认按钮")
                    AgentModelClient.ToolResult(
                        content = """{"ok":true,"observation_id":"o1"}""",
                        images = listOf(screenshot),
                    )
                } else {
                    AgentModelClient.ToolResult(content = """{"ok":true}""")
                }
            },
            provider = provider,
            runController = controller,
        )

        val round2 = provider.requests[1]
        val round3 = provider.requests[2]
        // Round 2 尾部顺序必须为 [tool, _eta_steering, _eta_observation]，瞬态截图留在最末位。
        assertEquals("tool", round2.getJSONObjectFromEnd(3).getString("role"))
        assertTrue(round2.getJSONObjectFromEnd(2).optBoolean("_eta_steering"))
        assertTrue(round2.getJSONObjectFromEnd(1).optBoolean("_eta_observation"))
        // Round 3 摘除瞬态截图后，到 _eta_steering 为止的整个前缀与 Round 2 逐条完全一致。
        val stablePrefixLength = round2.length() - 1
        assertTrue(round3.length() > stablePrefixLength)
        for (index in 0 until stablePrefixLength) {
            assertEquals(
                "第 $index 条消息前缀应保持逐字节稳定以命中缓存",
                round2.getJSONObject(index).toString(),
                round3.getJSONObject(index).toString(),
            )
        }
    }
}
