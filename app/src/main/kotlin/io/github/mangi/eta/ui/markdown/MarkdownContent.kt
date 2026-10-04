package io.github.mangi.eta.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 绘制一份已解析的 [MarkdownDocument]。
 *
 * 每个块以源码偏移作为组合 key；未变化的块是同一个实例，组合期直接跳过。传入
 * [reveal] 时文本块接入逐字显现：排版仍然一次完成，帧间推进只让绘制失效。
 */
@Composable
internal fun MarkdownContent(
    document: MarkdownDocument,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    reveal: SmoothTextRevealCoordinator? = null,
) {
    val scope = remember(style, reveal) { MarkdownRenderScope(style, reveal) }
    MarkdownBlockColumn(blocks = document.blocks, scope = scope, compact = false, modifier = modifier)
}

@Immutable
private class MarkdownRenderScope(
    val style: MarkdownStyle,
    val reveal: SmoothTextRevealCoordinator?,
) {
    fun muted() = MarkdownRenderScope(style.muted(), reveal)
}

/** 嵌套列表的层级只影响 marker 字形，用 CompositionLocal 传递，块组件不必逐层转发。 */
private val LocalListDepth = compositionLocalOf { 0 }

@Composable
private fun MarkdownBlockColumn(
    blocks: List<MarkdownBlock>,
    scope: MarkdownRenderScope,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            key(block.offset) {
                val previous = blocks.getOrNull(index - 1)
                val gap = if (compact) {
                    markdownCompactGap(previous, scope.style.tone)
                } else {
                    markdownBlockGap(previous, block, scope.style.tone)
                }
                if (gap.value > 0f) Spacer(Modifier.height(with(density) { gap.toDp() }))
                MarkdownBlockView(block, scope)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, scope: MarkdownRenderScope) {
    when (block) {
        is MarkdownParagraph -> MarkdownText(block, scope.style.body, scope)
        is MarkdownHeading -> MarkdownText(
            block = block,
            style = scope.style.heading(block.level),
            scope = scope,
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownCode -> MarkdownCodeBlock(block, scope)
        is MarkdownQuote -> MarkdownQuoteBlock(block, scope)
        is MarkdownAlert -> MarkdownAlertBlock(block, scope)
        is MarkdownList -> MarkdownListBlock(block, scope)
        is MarkdownTable -> MarkdownTableBlock(block, scope)
        is MarkdownTableCell -> MarkdownText(block, scope.style.table, scope)
        is MarkdownRule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(0.5.dp)
                .background(scope.style.dividerColor),
        )
    }
}

@Composable
private fun MarkdownText(
    block: MarkdownTextBlock,
    style: TextStyle,
    scope: MarkdownRenderScope,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val reveal = scope.reveal
    // 空单元格没有可显现的文字，也不占用显现协调器的 key。
    if (reveal == null || block.text.isEmpty()) {
        Text(text = block.text, style = style, softWrap = softWrap, modifier = modifier)
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    // 显现裁剪按字素边界逐帧变化，关闭像素对齐可避免裁剪边缘抖动。
    val animatedStyle = remember(style) { style.copy(textMotion = TextMotion.Animated) }
    Text(
        text = block.text,
        style = animatedStyle,
        softWrap = softWrap,
        modifier = modifier.smoothTextReveal(revealState),
        onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) },
    )
}

// ── 代码块 ──────────────────────────────────────────────────────────────

@Composable
private fun MarkdownCodeBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    // Issue #101 階段2：塊級公式走 KaTeX 真渲染 + 原文兜底；普通代碼保持原樣。
    if (block.isMath) {
        MarkdownMathBlock(block, scope)
        return
    }
    val style = scope.style
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = style.codeBackground, cornerRadius = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code_block),
                style = MiuixTheme.textStyles.footnote2,
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = block.text.text, tint = style.secondaryColor)
        }
        // 代码不折行，长行横向滚动，保留缩进与对齐。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            MarkdownText(block, style.code, scope, softWrap = false)
        }
    }
}

@Composable
private fun MarkdownMathBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    val style = scope.style
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val latex = block.text.text
    val html = remember(latex, dark) {
        LatexRenderer.buildKatexHtml(latex, displayMode = true, dark = dark)
    }
    // 與 html 同 key：深色切換後失敗態重試，避免 WebView 永久缺席。
    var loadFailed by remember(html) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .squircleSurface(color = style.codeBackground, cornerRadius = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LaTeX",
                style = MiuixTheme.textStyles.footnote2,
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = latex, tint = style.secondaryColor)
        }
        if (!loadFailed) {
            LatexWebView(
                html = html,
                onFallback = { loadFailed = true },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // 原文兜底：渲染成功也保留（離線可讀、可複製）；WebView 失敗時它是唯一內容。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 4.dp),
        ) {
            MarkdownText(block, style.code, scope, softWrap = false)
        }
    }
}

@Composable
private fun CopyButton(text: String, tint: Color) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    IconButton(
        onClick = {
            @Suppress("DEPRECATION")
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        minWidth = 32.dp,
        minHeight = 32.dp,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_code),
            modifier = Modifier.size(14.dp),
            tint = if (copied) MiuixTheme.colorScheme.primary else tint,
        )
    }
}

// ── 引用与提示 ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownQuoteBlock(block: MarkdownQuote, scope: MarkdownRenderScope) {
    val barColor = scope.style.dividerColor
    val mutedScope = remember(scope) { scope.muted() }
    MarkdownBlockColumn(
        blocks = block.blocks,
        scope = mutedScope,
        compact = false,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawLine(
                    color = barColor,
                    start = Offset(width / 2, width / 2),
                    end = Offset(width / 2, size.height - width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 14.dp),
    )
}

@Composable
private fun MarkdownAlertBlock(block: MarkdownAlert, scope: MarkdownRenderScope) {
    val accent = block.kind.accentColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = accent.copy(alpha = 0.08f), cornerRadius = 12.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = block.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(block.kind.label),
                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
        if (block.blocks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MarkdownBlockColumn(blocks = block.blocks, scope = scope, compact = false)
        }
    }
}

private val MarkdownAlertKind.icon: ImageVector
    get() = when (this) {
        MarkdownAlertKind.Note -> Icons.Rounded.Info
        MarkdownAlertKind.Tip -> Icons.Rounded.Lightbulb
        MarkdownAlertKind.Important -> Icons.Rounded.Feedback
        MarkdownAlertKind.Warning -> Icons.Rounded.Warning
        MarkdownAlertKind.Caution -> Icons.Rounded.Report
    }

private val MarkdownAlertKind.label: Int
    get() = when (this) {
        MarkdownAlertKind.Note -> R.string.markdown_alert_note
        MarkdownAlertKind.Tip -> R.string.markdown_alert_tip
        MarkdownAlertKind.Important -> R.string.markdown_alert_important
        MarkdownAlertKind.Warning -> R.string.markdown_alert_warning
        MarkdownAlertKind.Caution -> R.string.markdown_alert_caution
    }

@Composable
private fun MarkdownAlertKind.accentColor(): Color = when (this) {
    MarkdownAlertKind.Note -> MiuixTheme.colorScheme.primary
    MarkdownAlertKind.Tip -> StatusSuccess
    MarkdownAlertKind.Important -> AlertImportantColor
    MarkdownAlertKind.Warning -> StatusWarning
    MarkdownAlertKind.Caution -> MiuixTheme.colorScheme.error
}

private val AlertImportantColor = Color(0xFF8B5CF6)

// ── 列表 ────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownListBlock(block: MarkdownList, scope: MarkdownRenderScope) {
    val depth = LocalListDepth.current
    val answer = scope.style.tone == MarkdownTone.Answer
    val itemGap = when {
        block.loose -> if (answer) 10.dp else 8.dp
        else -> if (answer) 4.dp else 2.dp
    }
    // 序号按本列表最长的数字预留宽度，各项正文左边缘对齐。
    val markerWidth = if (block.ordered) {
        val digits = (block.items.lastOrNull()?.number ?: 1).toString().length
        (digits * 9 + 8).dp
    } else {
        14.dp
    }
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            key(item.offset) {
                if (index > 0) Spacer(Modifier.height(itemGap))
                Row(Modifier.fillMaxWidth()) {
                    ListMarker(item = item, ordered = block.ordered, depth = depth, width = markerWidth, scope = scope)
                    CompositionLocalProvider(LocalListDepth provides depth + 1) {
                        MarkdownBlockColumn(
                            blocks = item.blocks,
                            scope = scope,
                            compact = !block.loose,
                            modifier = Modifier
                                .weight(1f)
                                .alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ListMarker(
    item: MarkdownListItem,
    ordered: Boolean,
    depth: Int,
    width: Dp,
    scope: MarkdownRenderScope,
) {
    val style = scope.style
    val reveal = scope.reveal
    // 在 graphicsLayer 内读取显现进度：块开始显现只让 marker 图层更新，不触发重组。
    val visibility = Modifier.graphicsLayer {
        alpha = if (listMarkerVisible(reveal != null, item.markerRevealKey, reveal?.started?.value)) 1f else 0f
    }
    val markerModifier = visibility
        .padding(end = 8.dp)
        .widthIn(min = width)
    when {
        item.checked != null -> {
            val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
            Box(markerModifier.height(lineHeight), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (item.checked) MiuixTheme.colorScheme.primary else style.markerColor,
                )
            }
        }
        ordered -> Text(
            text = "${item.number}.",
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.End,
            modifier = markerModifier.alignByBaseline(),
        )
        else -> Text(
            text = BULLETS[depth % BULLETS.size],
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.Center,
            modifier = markerModifier.alignByBaseline(),
        )
    }
}

/**
 * 流式正文里 marker 与该项第一段文字同时出现；没有文字的空项在显现期间保持隐藏，
 * 避免先冒出孤立的圆点。
 */
internal fun listMarkerVisible(
    revealActive: Boolean,
    markerKey: RevealBlockKey?,
    started: Set<RevealBlockKey>?,
): Boolean = !revealActive || (markerKey != null && started?.contains(markerKey) == true)

private val BULLETS = listOf("•", "◦", "▪")

// ── 表格 ────────────────────────────────────────────────────────────────

/**
 * 列宽按内容自适应：每列取最宽单元格的自然宽度并限制在合理区间；总宽不足时按比例
 * 撑满可用宽度，超出时整体横向滚动。网格线与表头底色在绘制阶段按测量结果画出。
 */
@Composable
private fun MarkdownTableBlock(block: MarkdownTable, scope: MarkdownRenderScope) {
    val style = scope.style
    val bodyStyles = remember(style, block.alignments) {
        block.alignments.map { align -> style.table.copy(textAlign = align.textAlign) }
    }
    val headerStyles = remember(bodyStyles) {
        bodyStyles.map { it.copy(fontWeight = FontWeight.SemiBold) }
    }
    var grid by remember { mutableStateOf(TableGrid.Empty) }
    val lineColor = style.dividerColor
    val headerColor = style.tableHeaderBackground
    val columns = block.header.size
    val rows = remember(block) { listOf(block.header) + block.rows }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Layout(
                modifier = Modifier
                    .squircleBorder(width = 0.5.dp, color = lineColor, cornerRadius = 12.dp)
                    .squircleClip(cornerRadius = 12.dp)
                    .drawBehind {
                        val current = grid
                        val headerHeight = current.rowHeights.firstOrNull() ?: return@drawBehind
                        drawRect(headerColor, size = Size(size.width, headerHeight.toFloat()))
                        val stroke = 0.5.dp.toPx()
                        var y = 0f
                        current.rowHeights.dropLast(1).forEach { height ->
                            y += height
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                        }
                        var x = 0f
                        current.columnWidths.dropLast(1).forEach { width ->
                            x += width
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                        }
                    },
                content = {
                    rows.forEachIndexed { rowIndex, row ->
                        row.forEachIndexed { column, cell ->
                            MarkdownText(
                                block = cell,
                                style = if (rowIndex == 0) headerStyles[column] else bodyStyles[column],
                                scope = scope,
                                // 单元格以固定宽度测量，不需要 fill；fill 会干扰列宽的固有尺寸查询。
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                },
            ) { measurables, _ ->
                val rowCount = measurables.size / columns
                val minWidth = TableMinColumnWidth.roundToPx()
                val maxWidth = TableMaxColumnWidth.roundToPx()
                val widths = IntArray(columns) { column ->
                    (0 until rowCount).maxOf { row ->
                        measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity)
                    }.coerceIn(minWidth, maxWidth)
                }
                val natural = widths.sum()
                if (natural in 1 until availableWidth) {
                    val extra = availableWidth - natural
                    var assigned = 0
                    for (column in 0 until columns) {
                        val share = if (column == columns - 1) {
                            extra - assigned
                        } else {
                            (extra.toLong() * widths[column] / natural).toInt()
                        }
                        widths[column] += share
                        assigned += share
                    }
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixedWidth(widths[index % columns]))
                }
                val heights = IntArray(rowCount) { row ->
                    (0 until columns).maxOf { column -> placeables[row * columns + column].height }
                }
                val next = TableGrid(widths.toList(), heights.toList())
                if (next != grid) grid = next
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, y)
                            x += widths[column]
                        }
                        y += heights[row]
                    }
                }
            }
        }
    }
}

/** 测量结果供绘制阶段画网格；只在绘制中读取，写入不会引起重组。 */
@Immutable
private data class TableGrid(val columnWidths: List<Int>, val rowHeights: List<Int>) {
    companion object {
        val Empty = TableGrid(emptyList(), emptyList())
    }
}

private val MarkdownColumnAlign.textAlign: TextAlign
    get() = when (this) {
        MarkdownColumnAlign.Start -> TextAlign.Start
        MarkdownColumnAlign.Center -> TextAlign.Center
        MarkdownColumnAlign.End -> TextAlign.End
    }

private val TableMinColumnWidth = 72.dp
private val TableMaxColumnWidth = 260.dp
