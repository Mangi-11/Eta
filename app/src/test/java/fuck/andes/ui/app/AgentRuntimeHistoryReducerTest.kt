package fuck.andes.ui.app

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.ui.model.AgentChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeHistoryReducerTest {
    @Test
    fun recoveryThenLiveDeliveryCommitsTranscriptOnlyOnce() {
        val initial = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "完成")
        )

        val recovered = AgentRuntimeHistoryReducer.apply(initial, "run-1", transcript)
        val live = AgentRuntimeHistoryReducer.apply(recovered.state, "run-1", transcript)

        assertTrue(live.alreadyApplied)
        assertEquals(transcript, live.state.history)
        assertEquals(listOf("run-1"), live.state.appliedRuntimeRunIds)
    }
}
