package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentRunsUiState(
    val runs: List<RunSummaryUi>,
)
