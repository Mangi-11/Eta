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
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    onAction: (AgentHomeAction) -> Unit,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AgentChatBody(
        messages = state.messages,
        input = state.input,
        isStreaming = state.isStreaming,
        thinkingEnabled = state.thinkingEnabled,
        pendingImages = state.pendingImages,
        onInputChange = { onAction(AgentHomeAction.InputChanged(it)) },
        onThinkingChange = { onAction(AgentHomeAction.ThinkingToggled(it)) },
        onSend = { onAction(AgentHomeAction.SendMessage) },
        onStop = { onAction(AgentHomeAction.StopRun) },
        onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
        onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
        onSuggestionClick = { prompt ->
            onAction(AgentHomeAction.InputChanged(prompt))
            onAction(AgentHomeAction.SendMessage)
        },
        onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
        onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
        isDrawerOpen = isDrawerOpen,
        modifier = modifier,
    )
}
