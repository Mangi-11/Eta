package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

@Composable
internal fun OptionalModelPickerContent(
    pickerState: AgentModelPickerUiState,
    selectedSecondaryModelId: String?,
    followTitle: String,
    followSubtitle: String,
    onSelectModel: (String?) -> Unit,
) {
    val followOption = remember(
        followTitle,
        followSubtitle,
        pickerState.selectedModel?.modelId,
        pickerState.selectedModel?.providerSourceType,
    ) {
        ModelPickerFollowOption(
            title = followTitle,
            subtitle = followSubtitle,
            modelId = pickerState.selectedModel?.modelId,
            sourceType = pickerState.selectedModel?.providerSourceType,
        )
    }
    ModelPickerSheetContent(
        pickerState = pickerState,
        selectedModelId = selectedSecondaryModelId,
        followOption = followOption,
        onSelectModel = onSelectModel,
    )
}
