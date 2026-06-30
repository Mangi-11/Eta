package fuck.andes.ui.screens.permissions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.PermissionHealthAction
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: fuck.andes.ui.model.PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "summary") {
            Text(
                text = "Agent 需要以下权限才能观察和操作手机。缺失的权限会影响部分工具可用性。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        items(
            items = state.items,
            key = { it.id },
        ) { item ->
            PermissionItemRow(
                item = item,
                onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
            )
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicComponent(
            title = item.title,
            summary = item.summary,
            startAction = {
                PermissionStatusIcon(status = item.status)
            },
            endActions = {
                item.primaryActionLabel?.let { label ->
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            },
            onClick = if (item.primaryActionLabel != null) onActionClick else null,
        )
    }
}

@Composable
private fun PermissionStatusIcon(status: PermissionStatusUi) {
    val tint = when (status) {
        PermissionStatusUi.Available -> MiuixTheme.colorScheme.primary
        PermissionStatusUi.Warning -> MiuixTheme.colorScheme.primary
        PermissionStatusUi.Missing,
        PermissionStatusUi.Disabled -> MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    when (status) {
        PermissionStatusUi.Available -> Icon(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
            tint = tint,
        )
        PermissionStatusUi.Warning -> Icon(
            imageVector = MiuixIcons.Report,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
            tint = tint,
        )
        PermissionStatusUi.Missing,
        PermissionStatusUi.Disabled -> Icon(
            imageVector = MiuixIcons.Close,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
            tint = tint,
        )
    }
}
