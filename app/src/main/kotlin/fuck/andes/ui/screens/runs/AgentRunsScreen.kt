package fuck.andes.ui.screens.runs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.RunSummaryRow
import fuck.andes.ui.model.AgentRunsAction
import fuck.andes.ui.model.AgentRunsUiState

@Composable
fun AgentRunsScreen(
    state: AgentRunsUiState,
    onAction: (AgentRunsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        items(
            items = state.runs,
            key = { it.runId },
        ) { run ->
            RunSummaryRow(
                run = run,
                onClick = { onAction(AgentRunsAction.OpenRun(run.runId)) },
            )
        }
    }
}
