package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String,
    val searchQuery: String,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
)

@Immutable
enum class ConversationModeUi(
    val label: String,
) {
    Chat("聊天"),
    PhoneAgent("手机"),
    Terminal("终端"),
    Automation("自动化"),
}
