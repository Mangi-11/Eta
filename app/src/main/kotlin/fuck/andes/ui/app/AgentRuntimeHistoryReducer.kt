package fuck.andes.ui.app

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.ui.model.AgentChatHomeUiState

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
    ): Outcome {
        if (runId in state.appliedRuntimeRunIds) {
            return Outcome(state, alreadyApplied = true)
        }
        return Outcome(
            state = state.copy(
                history = state.history + additions,
                appliedRuntimeRunIds = (state.appliedRuntimeRunIds + runId)
                    .takeLast(MAX_APPLIED_RUN_IDS),
            ),
            alreadyApplied = false,
        )
    }

    fun wasApplied(state: AgentChatHomeUiState, runId: String): Boolean =
        runId in state.appliedRuntimeRunIds

    private const val MAX_APPLIED_RUN_IDS = 128
}
