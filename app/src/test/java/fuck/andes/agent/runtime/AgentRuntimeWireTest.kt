package fuck.andes.agent.runtime

import fuck.andes.agent.model.AgentModelClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeWireTest {
    @Test
    fun legacyModelConfigJsonDefaultsBrowserToolsToEnabled() {
        val config = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """{"baseUrl":"https://api.openai.com/v1","apiKey":"test-key","model":"gpt-test","systemPrompt":"你是手机 Agent"}"""
        )

        assertEquals(true, config.browserTools)
    }

    @Test
    fun runRequestBundleRoundTripPreservesConfigHistoryAndImages() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-1",
            prompt = "继续分析",
            config = AgentModelClient.ModelConfig(
                providerSourceType = "bailian",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "test-key",
                model = "qwen3-max",
                systemPrompt = "你是手机 Agent",
                terminalTools = true,
                browserTools = true,
                thinkingEnabled = true,
                extraBodyJson = """{"thinking_budget":256}""",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    dataUrl = "data:image/png;base64,abc",
                    mimeType = "image/png",
                    bytes = 123,
                    width = 1080,
                    height = 2400,
                    source = "screenshot",
                )
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "上一轮问题",
                ),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "上一轮回答",
                ),
            ),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "overlay",
                payload = """{"package":"com.tencent.mm"}""",
            ),
        )

        val bundle = AgentRuntimeWire.toBundle(request)
        assertEquals(true, bundle.containsKey("browser_tools"))
        assertEquals(true, bundle.getBoolean("browser_tools"))
        val roundTripped = AgentRuntimeWire.runRequestFromBundle(bundle)

        assertEquals(request, roundTripped)
    }

    @Test
    fun legacyRunRequestBundleDefaultsBrowserToolsToEnabled() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-legacy",
            prompt = "读取网页",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                model = "gpt-test",
                systemPrompt = "你是手机 Agent",
                browserTools = false,
            ),
            images = emptyList(),
        )
        val legacyBundle = AgentRuntimeWire.toBundle(request).apply {
            remove("browser_tools")
        }

        val roundTripped = AgentRuntimeWire.runRequestFromBundle(legacyBundle)

        assertEquals(true, roundTripped.config.browserTools)
    }

    @Test
    fun browserToolArgumentsSummaryOmitsSensitiveInputAndUrlParts() {
        val summary = AgentModelClient.summarizeBrowserToolArguments(
            """{"action":"type","url":"https://user:password@example.com/private?q=token#fragment","text":"secret input"}"""
        )

        assertEquals("输入内容 · example.com", summary)
    }

    @Test
    fun browserToolResultSummaryKeepsSafeMetadataAndFailureState() {
        val summary = AgentModelClient.summarizeToolResult(
            toolName = "browser_use",
            result = AgentModelClient.ToolResult(
                content = """{"ok":false,"action":"get_readable","url":"https://user:password@example.com/private?q=token#fragment","title":"Example","text":"secret body","elements":[{},{}],"truncated":true}"""
            ),
        )

        assertEquals(
            "ok=false, action=get_readable, host=example.com, title=Example, text_chars=11, elements=2, truncated=true",
            summary,
        )
    }

    @Test
    fun openUriArgumentsSummaryOmitsPathCredentialsAndQuery() {
        val summary = AgentModelClient.summarizeOpenUriArguments(
            """{"uri":"https://user:password@example.com/private/access_token/value?q=secret#fragment"}"""
        )

        assertEquals("交给外部应用 · https · example.com", summary)
    }

    @Test
    fun eventBundleRoundTripPreservesReasoningAndUsage() {
        val events = listOf(
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
            ),
            AgentEvent.AssistantBlockDelta(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "思考",
            ),
            AgentEvent.AssistantBlockEnd(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                contentChars = 4,
            ),
            AgentEvent.AssistantReceived(
                round = 2,
                contentChars = 12,
                reasoningContent = "完整思考内容",
                toolNames = listOf("observe_screen", "input_text"),
            ),
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(
                    contextTokens = 4096,
                    inputTokens = 1200,
                    outputTokens = 320,
                    reasoningTokens = 80,
                    cachedTokens = 900,
                ),
            ),
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_abc",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            AgentEvent.ToolFinished(
                round = 2,
                toolCallId = "call_abc",
                name = "observe_screen",
                resultSummary = "ok=true, chars=120",
                imageCount = 1,
                imageBytes = 2048,
            ),
        )

        events.forEach { event ->
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        }
    }

    @Test
    fun runResultBundleRoundTripPreservesReasoningContent() {
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "最终回答",
            reasoningContent = "先分析问题，再调用工具，最后总结。",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "最终回答",
                    reasoningContent = "先分析问题，再调用工具，最后总结。",
                )
            ),
        )

        val roundTripped = AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result))

        assertEquals(result, roundTripped)
    }

    @Test
    fun entryHandoffBundleRoundTripPreservesEntrySurfacePolicy() {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = "breeno",
            payload = """{"userText":"打开微信"}""",
            dismissEntrySurfaceOnForegroundOperation = true,
        )

        val roundTripped = AgentRuntimeWire.entryHandoffFromBundle(AgentRuntimeWire.toBundle(handoff))

        assertEquals(handoff, roundTripped)
    }

    @Test
    fun entryHandoffDefaultsToKeepingEntrySurfaceVisible() {
        val handoff = AgentRuntimeWire.entryHandoffFromBundle(
            AgentRuntimeWire.toBundle(
                AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "app",
                    payload = "{}",
                )
            )
        )

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun legacyBreenoHandoffDefaultsToDismissingEntrySurface() {
        val bundle = AgentRuntimeWire.toBundle(
            AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "breeno",
                payload = "{}",
            )
        ).apply {
            remove("handoff_dismiss_entry_surface_on_foreground_operation")
        }

        val handoff = AgentRuntimeWire.entryHandoffFromBundle(bundle)

        assertEquals(true, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun legacyNonBreenoHandoffDefaultsToKeepingEntrySurfaceVisible() {
        val bundle = AgentRuntimeWire.toBundle(
            AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "app",
                payload = "{}",
            )
        ).apply {
            remove("handoff_dismiss_entry_surface_on_foreground_operation")
        }

        val handoff = AgentRuntimeWire.entryHandoffFromBundle(bundle)

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }
}
