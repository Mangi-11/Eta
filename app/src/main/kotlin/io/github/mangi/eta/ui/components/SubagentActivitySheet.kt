package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天时间线中的 Subagent 节点行：保持内联极简与留白，点击呼出实时检视面板。
 */
@Composable
internal fun SubagentActivityRow(
    message: ToolActivityMessageUi,
    snapshot: SubagentActivitySnapshot,
    elapsedSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    var showInspector by rememberSaveable(message.id) { mutableStateOf(false) }
    val running = message.status == ToolActivityStatusUi.Running
    val failed = message.status == ToolActivityStatusUi.Failed ||
        snapshot.status == SubagentActivitySnapshot.STATUS_FAILED
    val typeLabel = snapshot.subagentType.ifBlank { "subagent" }
    val taskTitle = snapshot.description.ifBlank { message.argumentsSummary.ifBlank { "Subagent" } }
    val subtitle = when {
        running -> snapshot.currentAction.takeIf { it.isNotBlank() }
            ?: if (snapshot.toolStepCount > 0) "已执行 ${snapshot.toolStepCount} 步" else "正在运行…"
        failed -> snapshot.error?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: message.outcomeLine()
            ?: "执行未完成"
        else -> when {
            snapshot.toolStepCount > 0 -> "完成 · ${snapshot.toolStepCount} 次工具调用"
            else -> message.outcomeLine() ?: "完成"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showInspector = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubagentTypeBadge(type = typeLabel, active = running)
                    Spacer(modifier = Modifier.width(6.dp))
                    ShimmerText(
                        text = taskTitle,
                        active = running,
                        style = MiuixTheme.textStyles.body2,
                        color = if (running) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failed && !running) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (elapsedSeconds != null && elapsedSeconds >= 3) {
                Text(
                    text = stringResource(R.string.tool_elapsed_seconds, elapsedSeconds),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "查看 Subagent 详情",
                modifier = Modifier
                    .padding(start = 6.dp, end = 8.dp)
                    .size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            Dialog(
                visible = showInspector,
                onDismissRequest = { showInspector = false },
                dark = dark,
                title = taskTitle,
                confirmAction = CouiDialogAction(
                    text = stringResource(R.string.action_close),
                    onClick = { showInspector = false },
                ),
                placement = CouiDialogPlacement.Bottom,
                material = rememberCouiDialogMaterial(dark),
                backdrop = rememberCouiWindowBackdrop(),
            ) {
                SubagentInspectorContent(
                    snapshot = snapshot,
                    running = running,
                    failed = failed,
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            OverlayBottomSheet(
                show = showInspector,
                title = taskTitle,
                onDismissRequest = { showInspector = false },
            ) {
                SubagentInspectorContent(
                    snapshot = snapshot,
                    running = running,
                    failed = failed,
                )
            }
        }
    }
}

@Composable
private fun SubagentTypeBadge(
    type: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (active) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = modifier
            .squircleSurface(color = containerColor, cornerRadius = 6.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = type,
            style = MiuixTheme.textStyles.footnote2.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun SubagentInspectorContent(
    snapshot: SubagentActivitySnapshot,
    running: Boolean,
    failed: Boolean,
) {
    val density = LocalDensity.current
    val maxSheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.76f
    var promptExpanded by rememberSaveable(snapshot.subagentId) { mutableStateOf(false) }
    val pulse = rememberDrawPhasePulse(active = running)
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCoui) {
                    Modifier.padding(horizontal = CouiDialogDefaults.contentPadding, vertical = 8.dp)
                } else {
                    Modifier
                        .heightIn(max = maxSheetHeight)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 顶部状态与元信息横条：留白克制，只展示关键身份与实时状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = pulse?.value ?: 1f }
                    .clip(CircleShape)
                    .background(
                        when {
                            running -> MiuixTheme.colorScheme.primary
                            failed -> StatusError
                            else -> StatusSuccess
                        },
                    ),
            )
            SubagentTypeBadge(type = snapshot.subagentType.ifBlank { "subagent" }, active = running)
            if (snapshot.subagentId.isNotBlank()) {
                Text(
                    text = snapshot.subagentId,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                )
            }
            if (snapshot.model.isNotBlank()) {
                Text(
                    text = "· ${snapshot.model}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        // 任务指令（Prompt）：浅色容器，长文本默认收起，点按展开
        if (snapshot.prompt.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 12.dp,
                    )
                    .clickable { promptExpanded = !promptExpanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (snapshot.resumed) "Prompt (Resumed)" else "Prompt",
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    )
                    ExpandChevron(
                        expanded = promptExpanded,
                        contentDescription = null,
                    )
                }
                Text(
                    text = snapshot.prompt,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = if (promptExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 内部执行时间线：沿用主聊天界面的 1dp 细线与圆点徽章
        if (snapshot.steps.isNotEmpty() || running) {
            Column(modifier = Modifier.fillMaxWidth()) {
                snapshot.steps.forEachIndexed { index, step ->
                    key(step.id) {
                        val isLastStep = index == snapshot.steps.lastIndex && !running
                        WorkTimelineNode(
                            icon = if (step.kind == SubagentStepSnapshot.KIND_THINKING) {
                                Icons.Rounded.Lightbulb
                            } else {
                                iconForTool(step.toolName)
                            },
                            tone = when (step.status) {
                                SubagentStepSnapshot.STATUS_RUNNING -> WorkNodeTone.Running
                                SubagentStepSnapshot.STATUS_FAILED -> WorkNodeTone.Failed
                                else -> WorkNodeTone.Done
                            },
                            isLast = isLastStep,
                            connectTop = index > 0,
                        ) {
                            SubagentStepRow(step = step)
                        }
                    }
                }
                if (running && snapshot.steps.none { it.status == SubagentStepSnapshot.STATUS_RUNNING }) {
                    WorkTimelineNode(
                        icon = Icons.Rounded.Lightbulb,
                        tone = WorkNodeTone.Running,
                        isLast = true,
                        connectTop = snapshot.steps.isNotEmpty(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = WorkRowHeight)
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            ShimmerText(
                                text = snapshot.currentAction.ifBlank { "正在运行…" },
                                active = true,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        // 错误信息
        val error = snapshot.error?.takeIf { it.isNotBlank() && failed }
        if (error != null) {
            Text(
                text = error,
                style = MiuixTheme.textStyles.footnote1,
                color = StatusError,
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(
                        color = StatusError.copy(alpha = 0.08f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // 实时或最终汇报（Output）
        if (snapshot.output.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (running) "Streaming" else "Summary",
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                )
                SelectionContainer {
                    Text(
                        text = snapshot.output,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = 12.dp,
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentStepRow(
    step: SubagentStepSnapshot,
) {
    val hasDetail = step.detail.isNotBlank()
    var expanded by rememberSaveable(step.id) {
        mutableStateOf(step.kind == SubagentStepSnapshot.KIND_THINKING && step.status == SubagentStepSnapshot.STATUS_RUNNING)
    }
    val running = step.status == SubagentStepSnapshot.STATUS_RUNNING
    val failed = step.status == SubagentStepSnapshot.STATUS_FAILED

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(8.dp))
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerText(
                text = step.title.ifBlank { step.toolName.ifBlank { "步骤" } },
                active = running,
                style = MiuixTheme.textStyles.body2,
                color = when {
                    running -> MiuixTheme.colorScheme.onSurface
                    failed -> StatusError
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                ExpandChevron(
                    expanded = expanded,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded && hasDetail) {
            SelectionContainer {
                Text(
                    text = step.detail,
                    style = if (step.kind == SubagentStepSnapshot.KIND_TOOL) {
                        MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MiuixTheme.textStyles.footnote2
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f),
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .squircleSurface(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            cornerRadius = 10.dp,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
