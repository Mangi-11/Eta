package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.couiButtonFeedback
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.suqi8.coui.kmp.squircle.SquircleShape as CouiShape
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单轮回答底部的轻量性能统计胶囊：
 * 平时仅展示最关键的速度/首字读数，点击弹出锚定统计卡片（模型用时、工具用时、TTFT、TPS）。
 */
@Composable
internal fun TurnStatsPill(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    if (!usage.hasPerformanceStats) return
    var open by remember { mutableStateOf(false) }
    val steps = (usage.stepCount ?: 1).coerceAtLeast(1)
    val llmMs = usage.llmMs ?: 0L
    val toolMs = usage.toolMs ?: 0L
    val ttftSteps = usage.ttftSteps ?: if (usage.ttftMs != null) 1 else 0
    val avgTtftMs = if (ttftSteps > 0 && usage.ttftMs != null) usage.ttftMs / ttftSteps else null
    val decodeTokens = usage.decodeTokens ?: usage.outputTokens
    val tps = if (usage.decodeMs != null && usage.decodeMs > 0L && decodeTokens != null && decodeTokens > 0) {
        decodeTokens.toDouble() / (usage.decodeMs.toDouble() / 1_000.0)
    } else {
        null
    }
    val speedText = tps?.let { "${formatTokensPerSecond(it)} tok/s" }
    val avgTtftText = avgTtftMs?.let { statDurationText(it) }
    val llmTimeText = if (llmMs > 0L) statDurationText(llmMs) else null
    val pillLabel = when {
        avgTtftText != null && speedText != null -> "$avgTtftText · $speedText"
        speedText != null -> speedText
        llmTimeText != null -> llmTimeText
        avgTtftText != null -> "TTFT $avgTtftText"
        else -> return
    }

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val restingTint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f)
    val activeBg = if (open) {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(AnswerActionSize)
                .clip(RoundedCornerShape(8.dp))
                .background(activeBg, RoundedCornerShape(8.dp))
                .then(
                    if (isCoui) {
                        Modifier.couiButtonFeedback(couiDark, interactionSource, enabled = true)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { open = !open },
                )
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = ChatActionIcons.Gauge,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = restingTint,
            )
            Text(
                text = pillLabel,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = restingTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (open) {
            ChatStatsPopoverPopup(onDismissRequest = { open = false }) {
                StatsSectionHeader(
                    icon = ChatActionIcons.Gauge,
                    title = stringResource(R.string.stats_session_title),
                    value = formatTurnsAndSteps(turns = 1, steps = steps),
                )
                StatsSectionDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (llmMs > 0L) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_llm_time),
                            value = statDurationText(llmMs),
                        )
                    }
                    if (toolMs > 0L) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_tool_time),
                            value = statDurationText(toolMs),
                        )
                    }
                    if (avgTtftMs != null && avgTtftMs > 0L) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_avg_ttft),
                            value = statDurationText(avgTtftMs),
                        )
                    }
                    if (speedText != null) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_output_speed),
                            value = speedText,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 输入框右下角上下文圆环弹出的统计卡片：
 * 整合当前上下文窗口占用、会话累计 Token 明细（含缓存命中率）以及整场会话性能统计。
 */
@Composable
internal fun ContextUsageStatsPopoverPopup(
    usage: AgentContextUsageUi,
    summaryText: String,
    detailText: String,
    canCompact: Boolean,
    onCompact: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val stats = usage.sessionStats
    val cacheHit = if (stats.promptTokens > 0 && stats.cachedTokens > 0) {
        formatCacheHitPercent(stats.cachedTokens, stats.promptTokens)
    } else {
        null
    }
    val avgTtftMs = if (stats.ttftSteps > 0) stats.ttftMs / stats.ttftSteps else null
    val sessionSpeed = if (stats.decodeMs > 0L && stats.decodeTokens > 0) {
        "${formatTokensPerSecond(stats.decodeTokens.toDouble() / (stats.decodeMs.toDouble() / 1_000.0))} tok/s"
    } else {
        null
    }

    ChatStatsPopoverPopup(onDismissRequest = onDismissRequest) {
        StatsSectionHeader(
            icon = ChatActionIcons.Database,
            title = stringResource(if (stats.hasTokenUsage) R.string.stats_token_usage else R.string.ui_contextual_usage_d12810),
            value = if (stats.hasTokenUsage) "${formatExactTokenCount(stats.totalTokens)} tok" else null,
        )
        StatsSectionDivider()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (usage.contextTokens != null && usage.contextWindow != null && usage.contextWindow > 0) {
                StatsDetailRow(
                    label = stringResource(R.string.ui_contextual_usage_d12810),
                    value = summaryText.replace(" tokens · ", " · "),
                )
            } else {
                Text(
                    text = detailText,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (stats.hasTokenUsage) {
                if (stats.promptTokens > 0 || stats.outputTokens > 0) {
                    if (cacheHit != null) {
                        StatsDetailRow(label = stringResource(R.string.stats_cache_hit), value = "$cacheHit%")
                    }
                    StatsDetailRow(
                        label = stringResource(R.string.stats_cache_miss),
                        value = "${formatExactTokenCount(stats.uncachedInputTokens)} tok",
                    )
                    if (stats.hasCacheWriteTokens || stats.cacheWriteTokens > 0) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_cache_write),
                            value = "${formatExactTokenCount(stats.cacheWriteTokens)} tok",
                        )
                    }
                    if (stats.cachedTokens > 0) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_cache_read),
                            value = "${formatExactTokenCount(stats.cachedTokens)} tok",
                        )
                    }
                    StatsOutputRow(
                        outputTokens = stats.outputTokens,
                        reasoningTokens = stats.reasoningTokens,
                    )
                }
                if (stats.compactionCount > 0) {
                    val lastIn = stats.lastCompactionPromptTokens ?: stats.compactionPromptTokens
                    val lastCached = stats.lastCompactionCachedTokens ?: stats.compactionCachedTokens
                    val lastHit = formatCacheHitPercent(lastCached, lastIn)
                    val label = when {
                        stats.compactionCount == 1 && stats.lastCompactionArchiveId.isNotBlank() ->
                            stringResource(R.string.stats_last_compaction_id, stats.lastCompactionArchiveId)
                        stats.compactionCount == 1 -> stringResource(R.string.stats_last_compaction)
                        else -> pluralStringResource(
                            R.plurals.stats_compaction_requests,
                            stats.compactionCount,
                            stats.compactionCount,
                        )
                    }
                    val lastHitLabel = lastHit?.let { stringResource(R.string.stats_hit_percent, it) }
                    val detailValue = buildString {
                        append("${formatExactTokenCount(lastIn)} tok")
                        lastHitLabel?.let { append(" · "); append(it) }
                    }
                    StatsDetailRow(label = label, value = detailValue)
                    if (stats.compactionCount > 1) {
                        StatsDetailRow(
                            label = stringResource(R.string.stats_compaction_total),
                            value = "${formatExactTokenCount(stats.compactionPromptTokens + stats.compactionOutputTokens)} tok",
                        )
                    }
                    stats.lastCompactionPaybackTurns?.let { paybackTurns ->
                        val paybackValue = if (stats.lastCompactionPaidBack) {
                            pluralStringResource(
                                R.plurals.stats_payback_done,
                                stats.lastCompactionTurnsAfter,
                                stats.lastCompactionTurnsAfter,
                            )
                        } else {
                            stringResource(
                                R.string.stats_payback_needed,
                                paybackTurns,
                                stats.lastCompactionTurnsAfter,
                            )
                        }
                        StatsDetailRow(label = stringResource(R.string.stats_compaction_payback), value = paybackValue)
                    }
                }
                if (stats.subagentCount > 0) {
                    val subLabel = if (stats.subagentCount == 1) {
                        stringResource(R.string.stats_subagent_single)
                    } else {
                        pluralStringResource(
                            R.plurals.stats_subagent_count,
                            stats.subagentCount,
                            stats.subagentCount,
                        )
                    }
                    val subHit = formatCacheHitPercent(stats.subagentCachedTokens, stats.subagentPromptTokens)
                    val subHitLabel = subHit?.let { stringResource(R.string.stats_hit_percent, it) }
                    val subValue = buildString {
                        append("${formatExactTokenCount(stats.subagentPromptTokens)} tok")
                        subHitLabel?.let { append(" · "); append(it) }
                    }
                    StatsDetailRow(label = subLabel, value = subValue)
                }
                if (stats.ttlOrPrefixMissTokens > 5_000) {
                    StatsDetailRow(
                        label = stringResource(R.string.stats_non_incremental_miss),
                        value = "${formatExactTokenCount(stats.ttlOrPrefixMissTokens)} tok",
                    )
                }
            }
        }

        if (stats.hasPerformanceStats) {
            Spacer(Modifier.height(14.dp))
            StatsSectionHeader(
                icon = ChatActionIcons.Gauge,
                title = stringResource(R.string.stats_session_title),
                value = formatTurnsAndSteps(turns = stats.turns, steps = stats.steps),
            )
            StatsSectionDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stats.llmMs > 0L) {
                    StatsDetailRow(
                        label = stringResource(R.string.stats_llm_time),
                        value = statDurationText(stats.llmMs),
                    )
                }
                if (stats.toolMs > 0L) {
                    StatsDetailRow(
                        label = stringResource(R.string.stats_tool_time),
                        value = statDurationText(stats.toolMs),
                    )
                }
                if (avgTtftMs != null && avgTtftMs > 0L) {
                    StatsDetailRow(
                        label = stringResource(R.string.stats_avg_ttft),
                        value = statDurationText(avgTtftMs),
                    )
                }
                if (sessionSpeed != null) {
                    StatsDetailRow(
                        label = stringResource(R.string.stats_output_speed),
                        value = sessionSpeed,
                    )
                }
            }
        }

        if (canCompact) {
            Spacer(Modifier.height(12.dp))
            val compactLabel = stringResource(R.string.context_compact_action)
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = compactLabel,
                    onClick = {
                        onDismissRequest()
                        onCompact()
                    },
                    role = CouiButtonRole.Primary,
                    minHeight = 34.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(
                    text = compactLabel,
                    onClick = {
                        onDismissRequest()
                        onCompact()
                    },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    cornerRadius = 17.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun ChatStatsPopoverPopup(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) { StatsPopoverPositionProvider(density) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val cardModifier = if (isCoui) {
            val shape = remember { CouiShape(18.dp, 2.2f) }
            val bgColor = CouiTheme.colorScheme.surface
            val borderColor = CouiTheme.colorScheme.divider
            Modifier
                .widthIn(min = 256.dp, max = 328.dp)
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 18.dp,
                        color = Color.Black,
                        alpha = if (couiDark) 0.38f else 0.10f,
                    ),
                )
                .clip(shape)
                .background(bgColor, shape)
                .border(0.5.dp, borderColor, shape)
        } else {
            Modifier
                .widthIn(min = 256.dp, max = 328.dp)
                .dropShadow(
                    shape = RoundedCornerShape(18.dp),
                    shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = 0.12f),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 18.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cornerRadius = 18.dp,
                )
        }

        Column(
            modifier = cardModifier.padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun StatsSectionHeader(
    icon: ImageVector,
    title: String,
    value: String?,
) {
    val primaryColor = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = primaryColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = primaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = primaryColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatsSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun StatsDetailRow(
    label: String,
    value: String,
) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsOutputRow(
    outputTokens: Int,
    reasoningTokens: Int,
) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.stats_output),
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = labelColor,
        )
        Spacer(Modifier.width(16.dp))
        val reasoningSuffix = if (reasoningTokens > 0) {
            stringResource(R.string.stats_output_reasoning_suffix, formatExactTokenCount(reasoningTokens))
        } else {
            null
        }
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = valueColor)) {
                append("${formatExactTokenCount(outputTokens)} tok")
            }
            if (reasoningSuffix != null) {
                withStyle(SpanStyle(color = labelColor)) {
                    append(reasoningSuffix)
                }
            }
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
            ),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

private class StatsPopoverPositionProvider(
    density: Density,
) : PopupPositionProvider {
    private val marginPx = with(density) { 12.dp.roundToPx() }
    private val verticalGapPx = with(density) { 8.dp.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val minX = marginPx
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(minX)
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = preferredX.coerceIn(minX, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - verticalGapPx
        val belowY = anchorBounds.bottom + verticalGapPx
        val y = if (aboveY >= marginPx || belowY + popupContentSize.height > windowSize.height - marginPx) {
            aboveY.coerceAtLeast(marginPx)
        } else {
            belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        }
        return IntOffset(x, y)
    }
}

@Composable
private fun formatTurnsAndSteps(turns: Int, steps: Int): String {
    val turnsText = pluralStringResource(R.plurals.stats_turns_count, turns, turns)
    val stepsText = pluralStringResource(R.plurals.stats_steps_count, steps, steps)
    return stringResource(R.string.stats_turns_steps_join, turnsText, stepsText)
}

@Composable
private fun statDurationText(ms: Long): String {
    val clampedMs = ms.coerceAtLeast(0L)
    val seconds = clampedMs / 1_000.0
    if (seconds < 60.0) {
        val tenths = (seconds * 10.0).roundToLong()
        val whole = tenths / 10L
        val decimal = tenths % 10L
        val secValue = if (decimal == 0L) whole.toString() else "$whole.$decimal"
        return stringResource(R.string.stats_duration_seconds, secValue)
    }
    val totalSeconds = seconds.roundToLong()
    val minutes = totalSeconds / 60L
    val remSeconds = totalSeconds % 60L
    return stringResource(R.string.stats_duration_min_sec, minutes, remSeconds)
}

internal fun formatStatDuration(ms: Long): String {
    val clampedMs = ms.coerceAtLeast(0L)
    val seconds = clampedMs / 1_000.0
    if (seconds < 60.0) {
        val tenths = (seconds * 10.0).roundToLong()
        val whole = tenths / 10L
        val decimal = tenths % 10L
        val secValue = if (decimal == 0L) whole.toString() else "$whole.$decimal"
        return AppStrings.get(R.string.stats_duration_seconds, "%1\$s秒", secValue)
    }
    val totalSeconds = seconds.roundToLong()
    val minutes = totalSeconds / 60L
    val remSeconds = totalSeconds % 60L
    return AppStrings.get(R.string.stats_duration_min_sec, "%1\$d分%2\$d秒", minutes, remSeconds)
}

internal fun formatTokensPerSecond(tps: Double): String {
    val clamped = tps.coerceAtLeast(0.0)
    if (clamped >= 10.0) {
        return clamped.roundToInt().toString()
    }
    val tenths = (clamped * 10.0).roundToInt()
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0) whole.toString() else "$whole.$decimal"
}

internal fun formatExactTokenCount(tokens: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(tokens.coerceAtLeast(0))

internal fun formatCacheHitPercent(cachedTokens: Int, promptTokens: Int): String? {
    if (promptTokens <= 0 || cachedTokens < 0) return null
    if (cachedTokens >= promptTokens) return "100"
    val ratio = cachedTokens.toDouble() / promptTokens.toDouble() * 100.0
    val rounded = ratio.roundToInt()
    if (rounded < 100) return rounded.toString()
    val oneDecimal = (ratio * 10.0).toInt() / 10.0
    return if (oneDecimal < 100.0) "%.1f".format(Locale.US, oneDecimal) else "99.9"
}
