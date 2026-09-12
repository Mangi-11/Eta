package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** live result 与 outbox recovery 共用的 history 幂等提交点。 */
internal object AgentRuntimeHistoryReducer {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ): Outcome {
        if (runId in state.appliedRuntimeRunIds) {
            return Outcome(state, alreadyApplied = true)
        }
        val validSnapshot = snapshot?.takeIf { it.operationId == runId }
        val consumedSupplements = maxOf(additions.count { it.role == "user" }, validSnapshot?.consumedSupplementCount ?: 0)
        val pendingSupplements = if (!retainPendingSupplements) emptyList() else state.messages.filterIsInstance<UserMessageUi>()
            .filter { it.id.startsWith("user-$runId-supplement-") }
            .sortedBy { it.id.substringAfterLast('-').toIntOrNull() ?: 0 }
            .drop(consumedSupplements)
            .map { AgentModelClient.buildUserHistoryMessage(it.content, emptyList()).copy(messageId = it.id) }
        val history = if (validSnapshot != null) {
            val currentTurns = state.history.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
            val extra = (currentTurns - validSnapshot.consumedUserTurns).coerceAtLeast(0)
            val users = state.history.indices.filter { state.history[it].role == "user" }
            val laterHistory = if (extra > 0) state.history.drop(users.takeLast(extra).first()) else emptyList()
            // 快照与 transcript 独立落盘；崩溃时仍须接上快照之后已完成的批次。
            val covered = validSnapshot.consumedTranscriptMessages ?: additions.size
            require(covered in 0..additions.size || additions.isEmpty()) { "Invalid context transcript boundary" }
            validSnapshot.messages + additions.drop(covered) + laterHistory + pendingSupplements
        } else state.history + additions + pendingSupplements
        return Outcome(
            state = state.copy(
                journal = state.journal.ifEmpty { state.history } + additions + pendingSupplements,
                history = history,
                appliedRuntimeRunIds = state.appliedRuntimeRunIds + runId,
            ),
            alreadyApplied = false,
        )
    }

    fun wasApplied(state: AgentChatHomeUiState, runId: String): Boolean =
        runId in state.appliedRuntimeRunIds

}
