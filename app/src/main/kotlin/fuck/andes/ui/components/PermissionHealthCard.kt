package fuck.andes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
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
fun PermissionHealthCard(
    state: PermissionHealthUiState,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val issueCount = state.items.count { it.status != PermissionStatusUi.Available }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onOpenPermissions,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "权限健康",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = if (issueCount == 0) "正常" else "${issueCount} 项需关注",
                    style = MiuixTheme.textStyles.body2,
                    color = if (issueCount == 0) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            state.items.take(3).forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionStatusIcon(item.status)
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLabel(item.status),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
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
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        PermissionStatusUi.Warning -> Icon(
            imageVector = MiuixIcons.Report,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        PermissionStatusUi.Missing,
        PermissionStatusUi.Disabled -> Icon(
            imageVector = MiuixIcons.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

private fun statusLabel(status: PermissionStatusUi): String = when (status) {
    PermissionStatusUi.Available -> "正常"
    PermissionStatusUi.Missing -> "缺失"
    PermissionStatusUi.Warning -> "异常"
    PermissionStatusUi.Disabled -> "未启用"
}
