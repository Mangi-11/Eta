package fuck.andes.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fuck.andes.ui.components.AgentChatBody
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentHomeAction

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [fuck.andes.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    onAction: (AgentHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AgentChatBody(
        messages = state.messages,
        input = state.input,
        isStreaming = state.isStreaming,
        thinkingEnabled = state.thinkingEnabled,
        onInputChange = { onAction(AgentHomeAction.InputChanged(it)) },
        onThinkingChange = { onAction(AgentHomeAction.ThinkingToggled(it)) },
        onSend = { onAction(AgentHomeAction.SendMessage) },
        onAttach = { onAction(AgentHomeAction.OpenAttachment) },
        onScreenContext = { onAction(AgentHomeAction.AttachScreenContext) },
        onVoice = { onAction(AgentHomeAction.StartVoice) },
        onSuggestionClick = { prompt ->
            onAction(AgentHomeAction.InputChanged(prompt))
            onAction(AgentHomeAction.SendMessage)
        },
        onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
        modifier = modifier,
    )
}
