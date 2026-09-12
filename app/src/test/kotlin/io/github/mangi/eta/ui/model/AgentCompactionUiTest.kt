package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionUiTest {
    @Test
    fun manualActionRequiresIdleCompletedHistory() {
        val empty = AgentChatUiState(emptyList(), input = "草稿", isStreaming = false, thinkingEnabled = false)
        assertFalse(empty.canCompactContext)
        val ready = empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "已完成")))
        assertTrue(ready.canCompactContext)
        assertFalse(ready.copy(isStreaming = true).canCompactContext)
        assertFalse(empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "仅摘要", contextSummary = true))).canCompactContext)
    }

    @Test
    fun compactionEstimateReplacesPreviousUsageUntilNextModelResponse() {
        val response = AgentMessageUi("answer", "答案", usage = TokenUsageUi(contextTokens = 30_000))
        val compaction = SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction,
            "已压缩", contextTokens = 3_000)
        val estimate = latestContextUsage(listOf(response, compaction), null)
        assertEquals(3_000, estimate.contextTokens)
        assertTrue(estimate.estimated)
        val next = latestContextUsage(listOf(response, compaction, response.copy(id = "next", usage = TokenUsageUi(contextTokens = 4_000))), null)
        assertEquals(4_000, next.contextTokens)
        assertFalse(next.estimated)
    }
}
