package fuck.andes.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fuck.andes.ui.components.AgentChatBody
import fuck.andes.ui.model.AgentChatAction
import fuck.andes.ui.model.AgentChatUiState

/**
 * 独立对话页：与首页聊天主舞台共用同一套消息/输入组件，
 * 区别仅在于顶部返回由 Shell 统一提供。
 */
@Composable
fun AgentChatScreen(
    state: AgentChatUiState,
    onAction: (AgentChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AgentChatBody(
        messages = state.messages,
        input = state.input,
        isStreaming = state.isStreaming,
        thinkingEnabled = state.thinkingEnabled,
        onInputChange = { onAction(AgentChatAction.InputChanged(it)) },
        onThinkingChange = { onAction(AgentChatAction.ThinkingToggled(it)) },
        onSend = { onAction(AgentChatAction.SendMessage) },
        onAttach = { /* 预留附件入口 */ },
        onScreenContext = { onAction(AgentChatAction.AttachScreenContext) },
        onVoice = { /* 预留语音入口 */ },
        onSuggestionClick = { prompt ->
            onAction(AgentChatAction.InputChanged(prompt))
            onAction(AgentChatAction.SendMessage)
        },
        onRunTraceClick = { /* 对话页暂不做 Run trace 展开 */ },
        modifier = modifier,
    )
}
