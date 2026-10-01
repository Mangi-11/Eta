package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentExternalRunProjectorTest {
    @Test
    fun activeExternalRunPreservesDraftAndPriorConversation() {
        val earlier = UserMessageUi("earlier", "上一轮")
        val state = AgentChatUiState(
            messages = listOf(earlier), input = "尚未发送的草稿", isStreaming = false, thinkingEnabled = false,
        )
        val prepared = AgentExternalRunProjector.prepare(state, "external-run", payload())

        assertEquals(state.input, prepared.input)
        assertEquals(earlier, prepared.messages.first())
        assertEquals("长任务", (prepared.messages[1] as UserMessageUi).content)
        assertTrue(prepared.isStreaming)
        assertTrue(prepared.thinkingEnabled)
    }

    @Test
    fun completionArchiveAndRepeatedAttachReuseLiveMessages() {
        val active = AgentExternalRunProjector.prepare(
            AgentChatUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false),
            "external-run", payload(),
        )
        val streamed = active.copy(messages = active.messages.dropLast(1) +
            AgentMessageUi("assistant-external-run-1", "已收到的增量", isStreaming = true))

        val preparedAgain = AgentExternalRunProjector.prepare(streamed, "external-run", payload())

        assertEquals(streamed.messages, preparedAgain.messages)
        assertEquals(1, preparedAgain.messages.filterIsInstance<UserMessageUi>().size)
    }

    private fun payload() = AgentExternalArchivePayload(
        userText = "长任务", conversationKey = "breeno-room", title = "小布任务", thinkingEnabled = true,
    )
}
