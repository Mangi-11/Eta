package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** External transcripts contain model/tool replies, but not the initiating user message. */
internal object ExternalConversationHistory {
    fun appendUser(state: AgentChatHomeUiState, runId: String, text: String): AgentChatHomeUiState {
        val user = AgentModelClient.buildUserHistoryMessage(text, emptyList()).copy(messageId = "user-$runId")
        return state.copy(
            history = state.history.let { if (it.any { message -> message.messageId == user.messageId }) it else it + user },
            journal = state.journal.ifEmpty { state.history }.let { if (it.any { message -> message.messageId == user.messageId }) it else it + user },
        )
    }

    /** Repair older imports without rebuilding tool history or expanding compacted summaries. */
    fun repair(conversationId: String, state: AgentChatHomeUiState): AgentChatHomeUiState {
        if (!conversationId.startsWith("archive-") && !conversationId.startsWith("assistant-")) return state
        fun restore(source: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> {
            val restored = source.toMutableList()
            state.messages.filterIsInstance<UserMessageUi>().forEach { user ->
                if (!user.id.startsWith("user-") || restored.any { it.messageId == user.id }) return@forEach
                val replyPrefix = "assistant-${user.id.removePrefix("user-")}"
                val reply = restored.indexOfFirst {
                    it.role == "assistant" && (it.messageId == replyPrefix || it.messageId.startsWith("$replyPrefix-"))
                }
                if (reply >= 0) restored.add(reply,
                    AgentModelClient.buildUserHistoryMessage(user.content, emptyList()).copy(messageId = user.id))
            }
            return restored
        }
        return state.copy(history = restore(state.history), journal = restore(state.journal.ifEmpty { state.history }))
    }
}
