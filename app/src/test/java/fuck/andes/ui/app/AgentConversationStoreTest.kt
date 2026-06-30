package fuck.andes.ui.app

import android.content.Context
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent_conversations", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "看一下当前屏幕",
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "需要先观察屏幕",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "observe_screen",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "{}",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            input = "不应该保存草稿",
            isStreaming = true,
            thinkingEnabled = true,
        )

        AgentConversationStore.save(
            context = context,
            selectedConversationId = "conv-1",
            conversationsById = mapOf("conv-1" to conversation),
            titles = mapOf("conv-1" to "屏幕分析"),
            updatedAt = mapOf("conv-1" to 1234L),
        )

        val snapshot = AgentConversationStore.load(context, defaultThinkingEnabled = false)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("屏幕分析", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(conversation.messages, restored.messages)
    }

    @Test
    fun loadFallsBackWhenJsonIsInvalid() {
        context.getSharedPreferences("agent_conversations", Context.MODE_PRIVATE)
            .edit()
            .putString("state", "{broken")
            .commit()

        val snapshot = AgentConversationStore.load(context, defaultThinkingEnabled = true)

        assertEquals(1, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue(snapshot.selectedConversationId)
        assertTrue(restored.messages.isEmpty())
        assertTrue(restored.thinkingEnabled)
    }
}
