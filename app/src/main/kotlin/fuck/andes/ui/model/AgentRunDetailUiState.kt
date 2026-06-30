package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentRunDetailUiState(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val startedAt: String,
    val finishedAt: String?,
    val durationLabel: String?,
    val timeline: List<RunTimelineItemUi>,
)

@Immutable
sealed interface RunTimelineItemUi {
    val id: String

    @Immutable
    data class UserRequest(
        override val id: String,
        val content: String,
    ) : RunTimelineItemUi

    @Immutable
    data class ModelThinking(
        override val id: String,
        val content: String,
    ) : RunTimelineItemUi

    @Immutable
    data class ToolCall(
        override val id: String,
        val toolName: String,
        val argumentsSummary: String,
    ) : RunTimelineItemUi

    @Immutable
    data class ToolResult(
        override val id: String,
        val success: Boolean,
        val summary: String,
    ) : RunTimelineItemUi

    @Immutable
    data class Screenshot(
        override val id: String,
        val description: String,
    ) : RunTimelineItemUi

    @Immutable
    data class TerminalOutput(
        override val id: String,
        val command: String,
        val outputPreview: String,
    ) : RunTimelineItemUi

    @Immutable
    data class FinalResult(
        override val id: String,
        val content: String,
    ) : RunTimelineItemUi

    @Immutable
    data class Error(
        override val id: String,
        val message: String,
    ) : RunTimelineItemUi
}
