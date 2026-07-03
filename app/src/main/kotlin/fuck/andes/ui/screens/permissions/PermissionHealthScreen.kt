package fuck.andes.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.ArrowItem
import fuck.andes.ui.components.PrefDivider
import fuck.andes.ui.components.color
import fuck.andes.ui.components.label
import fuck.andes.ui.model.PermissionHealthAction
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "title") {
            SmallTitle("权限与状态")
        }
        item(key = "card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                state.items.forEachIndexed { index, item ->
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                    )
                    if (index < state.items.lastIndex) {
                        PrefDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    val icon = when (item.id) {
        "accessibility" -> LucideR.drawable.lucide_ic_accessibility
        "overlay" -> LucideR.drawable.lucide_ic_layers
        "model" -> LucideR.drawable.lucide_ic_cpu
        "terminal" -> LucideR.drawable.lucide_ic_square_terminal
        "notification" -> LucideR.drawable.lucide_ic_bell
        "root" -> LucideR.drawable.lucide_ic_key
        "shizuku" -> LucideR.drawable.lucide_ic_cpu
        "xposed" -> LucideR.drawable.lucide_ic_git_branch
        "background" -> LucideR.drawable.lucide_ic_history
        "app_list" -> LucideR.drawable.lucide_ic_layout_grid
        else -> LucideR.drawable.lucide_ic_shield
    }

    ArrowItem(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
