package io.github.mangi.eta.ui.components.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusWarning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val progress = usage.progress
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    // 常态低占用采用中性细环，避免小比例蓝色缺口在输入栏里看起来像卡住的加载圈；高水位再亮起预警色。
    val progressColor = when {
        progress == null -> ink.copy(alpha = if (isDark) 0.36f else 0.32f)
        progress >= 0.92f -> StatusError
        progress >= 0.75f -> StatusWarning
        else -> ink.copy(alpha = if (isDark) 0.48f else 0.42f)
    }
    val trackColor = ink.copy(alpha = if (isDark) 0.12f else 0.08f)
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
    var showPopover by remember { mutableStateOf(false) }
    val popupAnchor = rememberGlassPopupAnchor()
    val indicatorContent: @Composable () -> Unit = {
        CircularProgressIndicator(
            progress = progress ?: 0f,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = progressColor,
                disabledForegroundColor = progressColor,
                backgroundColor = trackColor,
            ),
            strokeWidth = 1.8.dp,
            size = 16.dp,
            modifier = Modifier.semantics {
                contentDescription = usageDescription
            },
        )
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopover = !showPopover },
            minWidth = 34.dp,
            minHeight = 34.dp,
            modifier = Modifier.glassPopupAnchor(anchor = popupAnchor, cornerRadius = 17.dp),
        ) {
            Box(modifier = Modifier.glassPopupAnchorContent(popupAnchor)) {
                indicatorContent()
            }
        }
        ContextUsageStatsPopoverPopup(
            usage = usage,
            summaryText = summary,
            detailText = detail,
            canCompact = canCompact,
            onCompact = onCompact,
            onDismissRequest = { showPopover = false },
            show = showPopover,
            anchor = popupAnchor,
            anchorContent = indicatorContent,
        )
    }
}
