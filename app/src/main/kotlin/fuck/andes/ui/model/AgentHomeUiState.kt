package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

/**
 * 首屏 AgentChatHome 的状态。
 * 当前与 AgentChatUiState 结构相同，保留独立类型以便后续扩展首页专属字段。
 */
typealias AgentChatHomeUiState = AgentChatUiState

@Immutable
data class ActiveRunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val elapsedLabel: String,
    val currentStep: String,
)

@Immutable
data class RunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val timeLabel: String,
    val toolCount: Int,
    val durationLabel: String,
)

@Immutable
enum class RunStatusUi {
    Running,
    Success,
    Failed,
    Cancelled,
}
