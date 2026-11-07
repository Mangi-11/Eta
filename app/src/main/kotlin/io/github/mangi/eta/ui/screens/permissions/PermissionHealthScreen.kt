package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.ui_permission_health_3048bb),
                onBack = { onAction(PermissionHealthAction.NavigateBack) },
                modifier = modifier,
            ) {
                Group(title = stringResource(R.string.ui_permissions_and_status_35f368)) {
                    state.items.forEachIndexed { index, item ->
                        PermissionItemRow(
                            item = item,
                            onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                        )
                        if (index < state.items.lastIndex) {
                            Divider()
                        }
                    }
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.ui_permission_health_3048bb),
                onBack = { onAction(PermissionHealthAction.NavigateBack) },
                modifier = modifier,
            ) {
                item(key = "card") {
                    SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        state.items.forEach { item ->
                            PermissionItemRow(
                                item = item,
                                onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                            )
                        }
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
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreference(
                title = item.title,
                summary = item.summary.takeIf { it.isNotBlank() },
                value = item.status.label(),
                valueColor = item.status.color(),
                onClick = onActionClick,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            ArrowPreference(
                title = item.title,
                summary = item.summary.takeIf { it.isNotBlank() },
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
    }
}
