package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleGeneratorTest {
    private val requests = mutableListOf<ProviderRequest>()

    private fun config(capabilities: ModelReasoningCapabilities? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1",
        apiKey = "fixture",
        model = "fixture-small",
        systemPrompt = "",
        reasoningEffort = ReasoningEffort.OFF,
        thinkingEnabled = false,
        reasoningCapabilities = capabilities,
    )

    private fun generator(
        config: AgentModelClient.ModelConfig? = config(),
        timeoutMs: Long = ConversationTitleGenerator.TIMEOUT_MS,
        reply: (AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse,
    ) = ConversationTitleGenerator(
        loadConfig = { config },
        providerFor = {
            object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = OpenAiChatCompletionsProvider.capabilities
                override fun complete(
                    request: ProviderRequest,
                    runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit,
                ): ProviderResponse = reply(runController, onEvent).also { requests += request }
            }
        },
        timeoutMs = timeoutMs,
    )

    private fun textReply(text: String, finish: String = "stop") = ProviderResponse(
        JSONObject()
            .put("role", "assistant")
            .put("content", text)
            .put("finish_reason", finish),
    )

    @Test
    fun generatesCleanTitleWithoutToolsAndStripsBoilerplate() = runBlocking {
        val promptWithFiles = AgentFileReferencePromptCodec.format(
            request = "帮我分析这个崩溃日志的原因",
            references = listOf(
                AgentFileReference(
                    displayName = "crash.txt",
                    absolutePath = "/sdcard/Download/crash.txt",
                    kind = AgentFileReferenceKind.File,
                ),
            ),
        )
        val title = generator { _, _ -> textReply("「崩溃日志原因分析。」\n补充说明忽略") }
            .generate(
                userPrompt = promptWithFiles,
                assistantReply = "根据堆栈信息，空指针发生在初始化阶段。",
                conversationModelId = "primary-model",
            )

        assertEquals("崩溃日志原因分析", title)
        val request = requests.single()
        assertEquals(ProviderRequestPurpose.TITLE_GENERATION, request.purpose)
        assertEquals(0, request.effectiveTools.length())
        assertEquals(
            ConversationTitleGenerator.SYSTEM_PROMPT,
            request.messages.getJSONObject(0).getString("content"),
        )
        val userPayload = request.messages.getJSONObject(1).getString("content")
        assertTrue(userPayload.contains("帮我分析这个崩溃日志的原因"))
        assertTrue(!userPayload.contains("/sdcard/Download/crash.txt"))
    }

    @Test
    fun sanitizeTitleRemovesPrefixesQuotesMarkdownAndCapsLength() {
        assertEquals("Android Gradle Upgrade", ConversationTitleGenerator.sanitizeTitle("Title: \"Android Gradle Upgrade.\""))
        assertEquals("Jetpack Compose 主题优化", ConversationTitleGenerator.sanitizeTitle("**标题：《Jetpack Compose 主题优化》**"))
        assertEquals("Python JSON Parsing", ConversationTitleGenerator.sanitizeTitle("# `Python JSON Parsing`!"))
        assertNull(ConversationTitleGenerator.sanitizeTitle("   "))
        assertNull(ConversationTitleGenerator.sanitizeTitle("null"))

        val longTitle = "超长标题".repeat(20)
        val sanitized = ConversationTitleGenerator.sanitizeTitle(longTitle)!!
        assertEquals(ConversationTitleGenerator.MAX_TITLE_CHARS, sanitized.length)

        // 不截断代理对高半区
        val emojiBoundary = "A".repeat(ConversationTitleGenerator.MAX_TITLE_CHARS - 1) + "\uD83D\uDE00"
        val truncatedEmoji = ConversationTitleGenerator.sanitizeTitle(emojiBoundary)!!
        assertEquals(ConversationTitleGenerator.MAX_TITLE_CHARS - 1, truncatedEmoji.length)
    }

    @Test
    fun returnsNullWhenDisabledEmptyOrIncomplete() = runBlocking {
        assertNull(generator(config = null) { _, _ -> error("should not invoke") }.generate("hello", "world"))
        assertNull(generator { _, _ -> error("should not invoke") }.generate("  ", "  "))
        assertNull(generator { _, _ -> textReply("半截标题", finish = "length") }.generate("hello", "world"))
    }

    @Test
    fun supportsImageOnlyUserTurnUsingAssistantReply() = runBlocking {
        val title = generator { _, _ -> textReply("植物品种识别") }
            .generate(userPrompt = "", assistantReply = "这张照片里的植物是龟背竹。")
        assertEquals("植物品种识别", title)
        val userPayload = requests.single().messages.getJSONObject(1).getString("content")
        assertTrue(userPayload.contains("[Sent image attachment]"))
        assertTrue(userPayload.contains("龟背竹"))
    }

    @Test
    fun rejectsToolCallsAndCancelsOnTimeout() {
        val toolCallFailure = runCatching {
            runBlocking {
                generator { _, onEvent ->
                    onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 0, "c1", "terminal"))
                    textReply("unused")
                }.generate("hello", "world")
            }
        }.exceptionOrNull()
        assertTrue(toolCallFailure is IllegalStateException)

        val cancelled = CountDownLatch(1)
        val timeoutFailure = runCatching {
            runBlocking {
                generator(timeoutMs = 50L) { controller, _ ->
                    val released = CountDownLatch(1)
                    controller.register {
                        cancelled.countDown()
                        released.countDown()
                    }
                    released.await(30, TimeUnit.SECONDS)
                    textReply("late")
                }.generate("hello", "world")
            }
        }.exceptionOrNull()
        assertTrue(timeoutFailure is TimeoutCancellationException)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
