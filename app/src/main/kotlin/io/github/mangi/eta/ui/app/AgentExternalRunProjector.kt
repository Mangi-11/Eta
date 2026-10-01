package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/** 活动订阅与完成归档共用同一组消息 ID；重复进入界面不添加第二份请求。 */
internal object AgentExternalRunProjector {
    fun prepare(
        state: AgentChatHomeUiState,
        runId: String,
        payload: AgentExternalArchivePayload,
        userImagePreviews: List<String> = emptyList(),
    ): AgentChatHomeUiState {
        val effort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: state.reasoningEffort
        val messages = if (state.messages.any { it.id == "user-$runId" }) {
            state.messages
        } else {
            state.messages + UserMessageUi(
                id = "user-$runId", content = payload.userText, images = userImagePreviews,
            ) + AgentMessageUi(
                id = "assistant-$runId", content = "", isStreaming = true, renderMarkdown = false,
            )
        }
        return state.copy(
            isStreaming = true,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            messages = messages,
        )
    }
}
