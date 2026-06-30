package fuck.andes.ui.model

sealed interface AgentHomeAction {
    data class InputChanged(val text: String) : AgentHomeAction
    data class ThinkingToggled(val enabled: Boolean) : AgentHomeAction
    data object SendMessage : AgentHomeAction
    data object AttachScreenContext : AgentHomeAction
    data object OpenAttachment : AgentHomeAction
    data object StartVoice : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenRuns : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentRunDetailAction {
    data object NavigateBack : AgentRunDetailAction
    data class RetryRun(val runId: String) : AgentRunDetailAction
}

sealed interface AgentChatAction {
    data object NavigateBack : AgentChatAction
    data class InputChanged(val text: String) : AgentChatAction
    data class ThinkingToggled(val enabled: Boolean) : AgentChatAction
    data object SendMessage : AgentChatAction
    data object AttachScreenContext : AgentChatAction
}

sealed interface AgentRunsAction {
    data object NavigateBack : AgentRunsAction
    data class OpenRun(val runId: String) : AgentRunsAction
}

sealed interface AgentToolsAction {
    data object NavigateBack : AgentToolsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data class ToggleItem(val itemId: String) : AgentSystemEnhanceAction
}
