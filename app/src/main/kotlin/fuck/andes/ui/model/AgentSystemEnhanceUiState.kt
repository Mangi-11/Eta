package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSystemEnhanceUiState(
    val sections: List<SystemEnhanceSectionUi>,
)

@Immutable
data class SystemEnhanceSectionUi(
    val id: String,
    val title: String,
    val items: List<SystemEnhanceItemUi>,
)

@Immutable
data class SystemEnhanceItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val status: SystemEnhanceStatusUi,
)

@Immutable
enum class SystemEnhanceStatusUi {
    Active,
    Inactive,
    Unsupported,
}
