package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaMonospace
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

/**
 * 面向独立滚动视口（如长篇 MEMORY.md 预览）的按需虚拟化 Markdown 视图。
 *
 * 将顶层块与顶层列表项拆分为 LazyColumn 条目，使数十 KiB 的长文档首帧只组合与测量当前视口内的少量节点，
 * 避免页面进入动画期间一次性排版整篇文档造成主线程掉帧。
 */
@Composable
internal fun LazyMarkdownContent(
    document: MarkdownDocument,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    overscrollEffect: OverscrollEffect? = null,
) {
    val scope = remember(style) { MarkdownRenderScope(style, reveal = null) }
    val density = LocalDensity.current
    val answer = style.tone == MarkdownTone.Answer
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        document.blocks.forEachIndexed { blockIndex, block ->
            val previous = document.blocks.getOrNull(blockIndex - 1)
            val blockGapSp = markdownBlockGap(previous, block, style.tone)
            if (block is MarkdownList && block.items.isNotEmpty()) {
                val markerWidth = listMarkerWidth(block)
                block.items.forEachIndexed { itemIndex, listItem ->
                    val prevItem = block.items.getOrNull(itemIndex - 1)
                    item(key = (block.offset.toLong() shl 32) or (listItem.offset.toLong() and 0xFFFFFFFFL)) {
                        if (itemIndex == 0) {
                            if (blockGapSp.value > 0f) {
                                Spacer(Modifier.height(with(density) { blockGapSp.toDp() }))
                            }
                        } else if (prevItem != null) {
                            Spacer(Modifier.height(listItemGap(prevItem, listItem, block.loose, answer)))
                        }
                        MarkdownListItemRow(
                            item = listItem,
                            ordered = block.ordered,
                            loose = block.loose,
                            depth = 0,
                            markerWidth = markerWidth,
                            scope = scope,
                        )
                    }
                }
            } else {
                item(key = block.offset) {
                    if (blockGapSp.value > 0f) {
                        Spacer(Modifier.height(with(density) { blockGapSp.toDp() }))
                    }
                    MarkdownBlockView(block, scope)
                }
            }
        }
    }
}

@Immutable
private class MarkdownRenderScope(
    val style: MarkdownStyle,
    val reveal: SmoothTextRevealCoordinator?,
    val inQuote: Boolean = false,
) {
    fun quoted() = MarkdownRenderScope(style.quoted(), reveal, inQuote = true)
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
                    markdownCompactGap(previous, block, scope.style.tone)
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
                .padding(vertical = 2.dp)
                .height(0.5.dp)
                .background(scope.style.ruleColor),
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
    val style = scope.style
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = style.codeBackground, cornerRadius = 12.dp)
            .squircleBorder(width = 0.5.dp, color = style.subtleBorderColor, cornerRadius = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .background(style.codeHeaderBackground)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code_block),
                style = MiuixTheme.textStyles.footnote2.copy(
                    fontFamily = if (block.language != null) EtaMonospace else null,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = block.text.text, tint = style.secondaryColor)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(style.subtleBorderColor),
        )
        // 代码不折行，长行横向滚动，保留缩进与对齐。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 11.dp),
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

private val QuoteContainerShape = RoundedCornerShape(
    topStart = 3.dp,
    bottomStart = 3.dp,
    topEnd = 10.dp,
    bottomEnd = 10.dp,
)

@Composable
private fun MarkdownQuoteBlock(block: MarkdownQuote, scope: MarkdownRenderScope) {
    val style = scope.style
    val barColor = style.quoteBarColor
    val quotedScope = remember(scope) { scope.quoted() }
    // 聊天中的顶层引用常承载模型拟好的回复话术或核心摘录，采用轻底色便签容器与细边条；
    // 嵌套引用只保留细内线，避免多层底色叠加显得笨重。
    val quoteModifier = if (scope.inQuote) {
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 2.dp.toPx()
                drawLine(
                    color = barColor,
                    start = Offset(width / 2, width / 2),
                    end = Offset(width / 2, size.height - width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(QuoteContainerShape)
            .background(style.quoteBackground)
            .drawBehind {
                drawRect(
                    color = barColor,
                    size = Size(2.5.dp.toPx(), size.height),
                )
            }
            .padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    }
    MarkdownBlockColumn(
        blocks = block.blocks,
        scope = quotedScope,
        compact = false,
        modifier = quoteModifier,
    )
}

@Composable
private fun MarkdownAlertBlock(block: MarkdownAlert, scope: MarkdownRenderScope) {
    val accent = block.kind.accentColor()
    val fillAlpha = if (scope.style.isDark) 0.10f else 0.055f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = accent.copy(alpha = fillAlpha), cornerRadius = 12.dp)
            .squircleBorder(width = 0.5.dp, color = accent.copy(alpha = 0.20f), cornerRadius = 12.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = block.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(block.kind.label),
                style = MiuixTheme.textStyles.body2.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                ),
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

private fun listMarkerWidth(block: MarkdownList): Dp = if (block.ordered) {
    val digits = (block.items.lastOrNull()?.number ?: 1).toString().length
    // 单位数序号起始贴齐正文左边界，多位数按最长序号预留槽位，保证列表项正文左边缘统一。
    if (digits <= 1) 18.dp else (digits * 9 + 6).dp
} else {
    14.dp
}

private fun MarkdownListItem.hasStructuredContent(): Boolean =
    blocks.size > 1 || blocks.any { it !is MarkdownParagraph }

private fun listItemGap(
    previous: MarkdownListItem,
    current: MarkdownListItem,
    loose: Boolean,
    answer: Boolean,
): Dp = when {
    loose -> if (answer) 11.dp else 8.dp
    previous.hasStructuredContent() || current.hasStructuredContent() -> if (answer) 10.dp else 7.dp
    else -> if (answer) 6.dp else 4.dp
}

@Composable
private fun MarkdownListBlock(block: MarkdownList, scope: MarkdownRenderScope) {
    val depth = LocalListDepth.current
    val answer = scope.style.tone == MarkdownTone.Answer
    val markerWidth = listMarkerWidth(block)
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            key(item.offset) {
                val previous = block.items.getOrNull(index - 1)
                if (previous != null) {
                    Spacer(Modifier.height(listItemGap(previous, item, block.loose, answer)))
                }
                MarkdownListItemRow(
                    item = item,
                    ordered = block.ordered,
                    loose = block.loose,
                    depth = depth,
                    markerWidth = markerWidth,
                    scope = scope,
                )
            }
        }
    }
}

@Composable
private fun MarkdownListItemRow(
    item: MarkdownListItem,
    ordered: Boolean,
    loose: Boolean,
    depth: Int,
    markerWidth: Dp,
    scope: MarkdownRenderScope,
) {
    val useBaselineAlignment = ordered && item.checked == null
    Row(Modifier.fillMaxWidth()) {
        ListMarker(item = item, ordered = ordered, depth = depth, width = markerWidth, scope = scope)
        CompositionLocalProvider(LocalListDepth provides depth + 1) {
            MarkdownBlockColumn(
                blocks = item.blocks,
                scope = scope,
                compact = !loose,
                modifier = Modifier
                    .weight(1f)
                    .then(if (useBaselineAlignment) Modifier.alignByBaseline() else Modifier),
            )
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
    val firstBlock = item.blocks.firstOrNull()
    val firstLineHeightSp = if (firstBlock is MarkdownHeading) {
        style.heading(firstBlock.level).lineHeight
    } else {
        style.body.lineHeight
    }
    val topInset = if (firstBlock is MarkdownQuote && !scope.inQuote) 8.dp else 0.dp
    // 在 graphicsLayer 内读取显现进度：块开始显现只让 marker 图层更新，不触发重组。
    val visibility = Modifier.graphicsLayer {
        alpha = if (listMarkerVisible(reveal != null, item.markerRevealKey, reveal?.started?.value)) 1f else 0f
    }
    val markerModifier = visibility
        .padding(end = 6.dp)
        .widthIn(min = width)
    when {
        item.checked != null -> {
            val lineHeight = with(LocalDensity.current) { firstLineHeightSp.toDp() }
            Box(
                modifier = markerModifier
                    .padding(top = topInset)
                    .height(lineHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (item.checked) MiuixTheme.colorScheme.primary else style.markerColor,
                )
            }
        }
        ordered -> Text(
            text = "${item.number}.",
            style = style.body.copy(fontWeight = FontWeight.Medium),
            color = style.markerColor,
            textAlign = TextAlign.Start,
            modifier = markerModifier.alignByBaseline(),
        )
        else -> {
            val lineHeight = with(LocalDensity.current) { firstLineHeightSp.toDp() }
            val bulletColor = style.markerColor
            val answer = style.tone == MarkdownTone.Answer
            Box(
                modifier = markerModifier
                    .padding(top = topInset)
                    .height(lineHeight)
                    .drawBehind {
                        // 自绘几何圆点替代字体自带的 "•"，跨 ColorOS / HyperOS 字库保持尺寸与视觉重心一致。
                        val diameter = (if (answer) 5.dp else 4.dp).toPx()
                        val center = Offset(x = diameter / 2f + 2.dp.toPx(), y = size.height * 0.52f)
                        when (depth % 3) {
                            0 -> drawCircle(color = bulletColor, radius = diameter / 2f, center = center)
                            1 -> drawCircle(
                                color = bulletColor,
                                radius = diameter / 2f,
                                center = center,
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                            else -> {
                                val side = (if (answer) 4.dp else 3.5.dp).toPx()
                                drawRoundRect(
                                    color = bulletColor,
                                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                                    size = Size(side, side),
                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                )
                            }
                        }
                    },
            )
        }
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
    val headerStyles = remember(bodyStyles, style.strongColor) {
        bodyStyles.map { it.copy(fontWeight = FontWeight.Bold, color = style.strongColor) }
    }
    var grid by remember { mutableStateOf(TableGrid.Empty) }
    val borderColor = style.subtleBorderColor
    val rowLineColor = style.subtleBorderColor
    val columnLineColor = style.subtleBorderColor.copy(alpha = style.subtleBorderColor.alpha * 0.65f)
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
                    .squircleBorder(width = 0.5.dp, color = borderColor, cornerRadius = 12.dp)
                    .squircleClip(cornerRadius = 12.dp)
                    .drawBehind {
                        val current = grid
                        val headerHeight = current.rowHeights.firstOrNull() ?: return@drawBehind
                        drawRect(headerColor, size = Size(size.width, headerHeight.toFloat()))
                        val stroke = 0.5.dp.toPx()
                        var y = 0f
                        current.rowHeights.dropLast(1).forEach { height ->
                            y += height
                            drawLine(rowLineColor, Offset(0f, y), Offset(size.width, y), stroke)
                        }
                        var x = 0f
                        current.columnWidths.dropLast(1).forEach { width ->
                            x += width
                            drawLine(columnLineColor, Offset(x, 0f), Offset(x, size.height), stroke)
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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
