package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)
    val progress = usage.progress
    val progressColor = when {
        progress == null -> MiuixTheme.colorScheme.onSurfaceVariantActions
        progress >= 0.95f -> StatusError
        progress >= 0.80f -> StatusWarning
        else -> MiuixTheme.colorScheme.primary
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val detail = when {
        usage.contextWindow == null -> summary
        usage.contextTokens == null -> stringResource(R.string.context_usage_after_response)
        else -> stringResource(R.string.context_usage_previous_response, summary)
    }
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val tooltipColors = TooltipDefaults.richTooltipColors()
    var showCouiDialog by remember { mutableStateOf(false) }
    val uiStyle = LocalUiStyle.current
    if (uiStyle == AppearanceUiStyle.COUI) {
        IconButton(
            onClick = { showCouiDialog = true },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
            modifier = modifier,
        ) {
            CircularProgressIndicator(
                progress = progress ?: 0f,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = progressColor,
                    disabledForegroundColor = progressColor,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                ),
                strokeWidth = 2.5.dp,
                size = ChatInputActionIconSize,
                modifier = Modifier.semantics {
                    contentDescription = usageDescription
                },
            )
        }
        if (canCompact) {
            CouiConfirmDialog(
                visible = showCouiDialog,
                title = stringResource(R.string.ui_contextual_usage_d12810),
                message = detail,
                confirmText = stringResource(R.string.context_compact_action),
                onConfirm = {
                    showCouiDialog = false
                    onCompact()
                },
                onDismissRequest = { showCouiDialog = false },
            )
        } else {
            CouiNoticeDialog(
                visible = showCouiDialog,
                title = stringResource(R.string.ui_contextual_usage_d12810),
                message = detail,
                onDismissRequest = { showCouiDialog = false },
            )
        }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                RichTooltip(
                    title = {
                        Text(
                            text = stringResource(R.string.ui_contextual_usage_d12810),
                            color = tooltipColors.titleContentColor,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    },
                    action = if (canCompact) {
                        {
                            TextButton(
                                text = stringResource(R.string.context_compact_action),
                                onClick = {
                                    onCompact()
                                    tooltipState.dismiss()
                                },
                                minWidth = 0.dp,
                                minHeight = 34.dp,
                                cornerRadius = 17.dp,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = tooltipColors,
                ) {
                    Text(
                        text = detail,
                        color = tooltipColors.contentColor,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            },
            state = tooltipState,
            focusable = true,
            modifier = modifier,
        ) {
            IconButton(
                onClick = { scope.launch { tooltipState.show() } },
                minWidth = ChatInputActionSize,
                minHeight = ChatInputActionSize,
            ) {
                CircularProgressIndicator(
                    progress = progress ?: 0f,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                        foregroundColor = progressColor,
                        disabledForegroundColor = progressColor,
                        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                    ),
                    strokeWidth = 2.5.dp,
                    size = ChatInputActionIconSize,
                    modifier = Modifier.semantics {
                        contentDescription = usageDescription
                    },
                )
            }
        }
    }
}
