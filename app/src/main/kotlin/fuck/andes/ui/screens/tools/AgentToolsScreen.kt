package fuck.andes.ui.screens.tools

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.SectionHeader
import fuck.andes.ui.components.ToolChip
import fuck.andes.ui.model.AgentToolsAction
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ToolGroupUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        items(
            items = state.groups,
            key = { it.id },
        ) { group ->
            ToolGroupCard(group = group)
        }
    }
}

@Composable
private fun ToolGroupCard(group: ToolGroupUi) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        SectionHeader(
            text = group.title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        group.tools.forEach { tool ->
            BasicComponent(
                title = tool.title,
                summary = tool.summary,
                endActions = {
                    ToolChip(text = tool.id)
                },
            )
        }
    }
}
