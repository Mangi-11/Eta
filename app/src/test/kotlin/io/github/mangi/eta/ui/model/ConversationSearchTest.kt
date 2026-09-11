package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {

    private val noticeText: (SystemNoticeCode) -> String = { code ->
        when (code) {
            SystemNoticeCode.Stopped -> "Stopped"
            SystemNoticeCode.EmptyResult -> "Empty result"
            SystemNoticeCode.ModelRetry -> "Retrying"
            SystemNoticeCode.RuntimeFailed -> "Runtime failed"
            SystemNoticeCode.Interrupted -> "Interrupted"
        }
    }

    private fun stateWith(vararg messages: AgentChatMessageUi) = AgentChatHomeUiState(
        messages = messages.toList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    @Test
    fun matchesUserAndAssistantTextCaseInsensitively() {
        val state = stateWith(
            UserMessageUi(id = "u", content = "今天天气怎么样"),
            AgentMessageUi(id = "a", content = "DeepSeek V4 很强"),
        )

        assertTrue(state.contentMatches("天气", noticeText))
        assertTrue(state.contentMatches("deepseek", noticeText))
        assertFalse(state.contentMatches("不存在", noticeText))
    }

    @Test
    fun matchesFileReferenceNameAndPathButNotEncodedHeaders() {
        val content = AgentFileReferencePromptCodec.format(
            request = "看一下这个文件",
            references = listOf(
                AgentFileReference("账单.xlsx", "/sdcard/Documents/账单.xlsx", AgentFileReferenceKind.File),
            ),
        )
        val state = stateWith(UserMessageUi(id = "u", content = content))

        assertTrue(state.contentMatches("账单", noticeText))
        assertTrue(state.contentMatches("/sdcard", noticeText))
        assertFalse(state.contentMatches("Files mentioned", noticeText))
        assertFalse(state.contentMatches("My request", noticeText))
    }

    @Test
    fun matchesThinkingContent() {
        val state = stateWith(
            ThinkingMessageUi(id = "t", content = "先观察屏幕再点击", isStreaming = false),
        )

        assertTrue(state.contentMatches("观察", noticeText))
        assertFalse(state.contentMatches("终端", noticeText))
    }

    @Test
    fun matchesToolActivityFields() {
        val state = stateWith(
            ToolActivityMessageUi(
                id = "tool",
                toolName = "run_terminal_command",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "列出目录",
                command = "ls -la /tmp",
                resultSummary = "total 5",
            ),
        )

        assertTrue(state.contentMatches("terminal", noticeText))
        assertTrue(state.contentMatches("列出目录", noticeText))
        assertTrue(state.contentMatches("/tmp", noticeText))
        assertTrue(state.contentMatches("total", noticeText))
        assertFalse(state.contentMatches("不存在", noticeText))
    }

    @Test
    fun matchesToolSummaryAndNotice() {
        val withSummary = stateWith(
            ToolSummaryMessageUi(id = "s", tools = listOf("observe_screen", "tap_element")),
        )
        val withNotice = stateWith(
            SystemNoticeMessageUi(id = "n", code = SystemNoticeCode.Stopped, detail = "用户主动取消"),
        )

        assertTrue(withSummary.contentMatches("observe", noticeText))
        assertTrue(withNotice.contentMatches("stopped", noticeText))
        assertTrue(withNotice.contentMatches("主动取消", noticeText))
        assertFalse(withNotice.contentMatches("runtime failed", noticeText))
    }

    @Test
    fun ignoresNonContentMessages() {
        val state = stateWith(
            RunTraceMessageUi(id = "r", capabilities = emptyList()),
            SuggestionChipsMessageUi(id = "s", prompts = listOf("试试这个")),
        )

        assertFalse(state.contentMatches("试试", noticeText))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(stateWith().contentMatches("  ", noticeText))
    }
}
