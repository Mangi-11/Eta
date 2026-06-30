package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val groups: List<ToolGroupUi>,
)

@Immutable
data class ToolGroupUi(
    val id: String,
    val title: String,
    val tools: List<ToolItemUi>,
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
)
