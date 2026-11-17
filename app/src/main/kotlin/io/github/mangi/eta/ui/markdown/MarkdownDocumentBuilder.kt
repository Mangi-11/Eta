package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * 把 GFM AST 转成 [MarkdownDocument]。
 *
 * 同一会话内连续构建时，源码切片未变的顶层块直接复用上一次的实例：流式输出只改动
 * 尾部块，前面的段落、公式、表格与代码块既不重新生成 AnnotatedString，组合期也因实例相同
 * 而跳过。行内样式或链接表变化（主题切换、终态补齐引用链接）时缓存整体失效。
 */
internal class MarkdownDocumentBuilder(
    private val mathCache: MarkdownMathLayoutCache = markdownMathLayoutCache,
) {
    private var cache = emptyMap<Int, CachedBlock>()
    private var cacheStyle: MarkdownInlineStyle? = null
    private var cacheLinks: LinkMap? = null

    fun build(
        root: ASTNode,
        renderedSource: String,
        originalSource: String = renderedSource,
        links: LinkMap?,
        style: MarkdownInlineStyle,
    ): MarkdownDocument {
        if (style != cacheStyle || links !== cacheLinks) {
            cache = emptyMap()
            cacheStyle = style
            cacheLinks = links
        }
        val converter = BlockConverter(
            source = renderedSource,
            originalSource = originalSource,
            links = links,
            style = style,
            mathCache = mathCache,
        )
        val nextCache = HashMap<Int, CachedBlock>()
        val blocks = root.children.mapNotNull { node ->
            val renderedSlice = renderedSource.substring(node.startOffset, node.endOffset)
            val originalEnd = node.endOffset.coerceAtMost(originalSource.length)
            val originalStart = node.startOffset.coerceAtMost(originalEnd)
            val originalSlice = originalSource.substring(originalStart, originalEnd)
            val reused = cache[node.startOffset]
                ?.takeIf { it.renderedSource == renderedSlice && it.originalSource == originalSlice }
                ?.block
            val block = reused ?: converter.convert(node) ?: return@mapNotNull null
            nextCache[node.startOffset] = CachedBlock(renderedSlice, originalSlice, block)
            block
        }
        cache = nextCache
        return MarkdownDocument(blocks = blocks, revealKeys = blocks.revealKeys(), inlineStyle = style)
    }

    private class CachedBlock(
        val renderedSource: String,
        val originalSource: String,
        val block: MarkdownBlock,
    )
}

private class BlockConverter(
    private val source: String,
    private val originalSource: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
    private val mathCache: MarkdownMathLayoutCache,
) {
    fun convert(node: ASTNode): MarkdownBlock? = when (node.type) {
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownElementTypes.LINK_DEFINITION,
        -> null

        MarkdownElementTypes.PARAGRAPH -> paragraph(node)
        GFMElementTypes.BLOCK_MATH -> blockMath(node)
        MarkdownElementTypes.ATX_1 -> heading(node, 1, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_2 -> heading(node, 2, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_3 -> heading(node, 3, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_4 -> heading(node, 4, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_5 -> heading(node, 5, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_6 -> heading(node, 6, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.SETEXT_1 -> heading(node, 1, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.SETEXT_2 -> heading(node, 2, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node)
        MarkdownElementTypes.CODE_BLOCK -> indentedCode(node)
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownQuote(node.startOffset, children(node))
        GFMElementTypes.ALERT -> alert(node)
        MarkdownElementTypes.UNORDERED_LIST -> list(node, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> list(node, ordered = true)
        GFMElementTypes.TABLE -> table(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownRule(node.startOffset)
        // 未支持的块（HTML 块等）按原文显示：宁可露出标记，也不能让内容凭空消失。
        else -> raw(node)
    }

    private fun children(node: ASTNode): List<MarkdownBlock> = node.children.mapNotNull(::convert)

    private fun paragraph(node: ASTNode): MarkdownBlock? {
        val meaningful = node.children.filterNot { it.isWhitespaceLike() }
        val math = meaningful.singleOrNull()?.takeIf {
            it.type == GFMElementTypes.BLOCK_MATH && !isInlineParenthesisMath(it)
        }
        if (math != null) {
            return blockMath(math, blockOffset = node.startOffset)
        }
        val content = inline(node.children)
        return if (content.text.isEmpty()) {
            null
        } else {
            MarkdownParagraph(node.startOffset, content.text, content.inlineMath)
        }
    }

    private fun blockMath(node: ASTNode, blockOffset: Int = node.startOffset): MarkdownBlock? {
        val extracted = extractBlockMath(node) ?: return null
        val displayList = mathCache.layout(latex = extracted.latex, displayMode = true)
        return MarkdownMath(
            offset = blockOffset,
            latex = extracted.latex,
            rawSource = extracted.rawSource,
            displayList = displayList,
        )
    }

    private fun heading(node: ASTNode, level: Int, contentType: IElementType): MarkdownBlock {
        val content = inline(node.child(contentType)?.children.orEmpty())
        return MarkdownHeading(node.startOffset, level, content.text, content.inlineMath)
    }

    /**
     * 围栏内容由 CODE_FENCE_CONTENT 与 EOL 交替组成；列表或引用里的缩进是独立的
     * WHITE_SPACE 节点，属于外层结构，拼接时跳过。
     */
    private fun codeFence(node: ASTNode): MarkdownBlock {
        val language = node.child(MarkdownTokenTypes.FENCE_LANG)
            ?.text()?.trim()?.substringBefore(' ')?.takeIf(String::isNotEmpty)
        val body = StringBuilder()
        var inBody = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_END -> break
                MarkdownTokenTypes.EOL -> if (inBody) body.append('\n') else inBody = true
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> if (inBody) body.append(child.text())
            }
        }
        if (body.endsWith('\n')) body.setLength(body.length - 1)
        return MarkdownCode(node.startOffset, language, AnnotatedString(body.toString()))
    }

    private fun indentedCode(node: ASTNode): MarkdownBlock {
        val body = buildString {
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> append(child.text().removeIndent(4))
                    MarkdownTokenTypes.EOL -> append('\n')
                }
            }
        }.trimEnd('\n')
        return MarkdownCode(node.startOffset, language = null, text = AnnotatedString(body))
    }

    private fun alert(node: ASTNode): MarkdownBlock {
        val title = node.child(GFMTokenTypes.ALERT_TITLE)?.text().orEmpty()
        val kind = when (title.removeSurrounding("[!", "]").uppercase()) {
            "TIP" -> MarkdownAlertKind.Tip
            "IMPORTANT" -> MarkdownAlertKind.Important
            "WARNING" -> MarkdownAlertKind.Warning
            "CAUTION" -> MarkdownAlertKind.Caution
            else -> MarkdownAlertKind.Note
        }
        val blocks = node.children.filterNot { it.type == GFMTokenTypes.ALERT_TITLE }.mapNotNull(::convert)
        return MarkdownAlert(node.startOffset, kind, blocks)
    }

    private fun list(node: ASTNode, ordered: Boolean): MarkdownBlock {
        val itemNodes = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (ordered) {
            itemNodes.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
                ?.text()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
        } else {
            null
        }
        val items = itemNodes.mapIndexed { index, item ->
            val blocks = item.children.mapNotNull { child ->
                when (child.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    GFMTokenTypes.CHECK_BOX,
                    -> null
                    else -> convert(child)
                }
            }
            MarkdownListItem(
                offset = item.startOffset,
                number = start?.plus(index),
                checked = item.child(GFMTokenTypes.CHECK_BOX)?.text()?.let { box -> 'x' in box || 'X' in box },
                blocks = blocks,
                markerRevealKey = blocks.firstRevealKey(),
            )
        }
        val loose = node.hasBlankLine() || itemNodes.any { it.hasBlankLine() }
        return MarkdownList(node.startOffset, ordered, loose, items)
    }

    private fun table(node: ASTNode): MarkdownBlock? {
        val header = node.child(GFMElementTypes.HEADER)?.cells() ?: return null
        val columns = header.size
        val alignments = node.children
            .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && '-' in it.text() }
            ?.text()?.parseAlignments(columns)
            ?: List(columns) { MarkdownColumnAlign.Start }
        // GFM：多余的单元格丢弃，缺少的补空，所有行与表头列数一致。
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = row.cells()
            List(columns) { column ->
                cells.getOrNull(column) ?: MarkdownTableCell(row.endOffset, AnnotatedString(""))
            }
        }
        return MarkdownTable(node.startOffset, alignments, header, rows)
    }

    private fun ASTNode.cells(): List<MarkdownTableCell> = children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell ->
            val content = inline(cell.children)
            MarkdownTableCell(cell.startOffset, content.text, content.inlineMath)
        }

    private fun raw(node: ASTNode): MarkdownBlock? {
        val text = node.text().trim()
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, AnnotatedString(text))
    }

    private fun inline(nodes: List<ASTNode>): MarkdownInlineContent = inlineContent(
        nodes = nodes,
        source = source,
        originalSource = originalSource,
        links = links,
        style = style,
        mathCache = mathCache,
    )

    private fun isInlineParenthesisMath(node: ASTNode): Boolean {
        val openDollar = node.children.firstOrNull { it.type == GFMTokenTypes.DOLLAR } ?: return false
        return openDollar.startOffset + 2 <= originalSource.length &&
            originalSource.startsWith("\\(", openDollar.startOffset)
    }

    private data class ExtractedMath(val latex: String, val rawSource: String)

    private fun extractBlockMath(node: ASTNode): ExtractedMath? {
        val openIdx = node.children.indexOfFirst { it.type == GFMTokenTypes.DOLLAR }
        val closeIdx = node.children.indexOfLast { it.type == GFMTokenTypes.DOLLAR }
        if (openIdx < 0 || closeIdx <= openIdx) {
            val raw = node.originalText().trim()
            return raw.takeIf(String::isNotEmpty)?.let { ExtractedMath(latex = it, rawSource = it) }
        }
        val openNode = node.children[openIdx]
        val closeNode = node.children[closeIdx]
        val bodyBuilder = StringBuilder()
        for (index in (openIdx + 1) until closeIdx) {
            val child = node.children[index]
            when (child.type) {
                // 引用块与列表外层标记不属于公式源码，跳过以免混入 `>` 或列表前缀。
                MarkdownTokenTypes.BLOCK_QUOTE,
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.LIST_NUMBER,
                GFMTokenTypes.CHECK_BOX,
                -> Unit
                MarkdownTokenTypes.EOL -> bodyBuilder.append('\n')
                else -> bodyBuilder.append(child.originalText())
            }
        }
        val rawBody = bodyBuilder.toString()
        val latex = rawBody.trim('\n', '\r', ' ', '\t')
        if (latex.isEmpty()) return null
        val openDelim = openNode.originalText()
        val closeDelim = closeNode.originalText()
        val rawSource = openDelim + rawBody + closeDelim
        return ExtractedMath(latex = latex, rawSource = rawSource)
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    private fun ASTNode.originalText(): String =
        if (endOffset <= originalSource.length) {
            originalSource.substring(startOffset, endOffset)
        } else {
            source.substring(startOffset, endOffset)
        }

    /**
     * 两个连续 EOL 即源码中的空行；出现在列表项之间或列表项内部时，该列表为松散列表。
     * 末尾的空行属于列表之后的内容，不计入。
     */
    private fun ASTNode.hasBlankLine(): Boolean {
        val lastContent = children.indexOfLast { it.type != MarkdownTokenTypes.EOL }
        return (1..lastContent).any { index ->
            children[index - 1].type == MarkdownTokenTypes.EOL && children[index].type == MarkdownTokenTypes.EOL
        }
    }

    private fun ASTNode.isWhitespaceLike(): Boolean =
        type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.EOL
}

private fun String.parseAlignments(columns: Int): List<MarkdownColumnAlign> {
    val specs = trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return List(columns) { index ->
        val spec = specs.getOrNull(index).orEmpty()
        when {
            spec.startsWith(':') && spec.endsWith(':') -> MarkdownColumnAlign.Center
            spec.endsWith(':') -> MarkdownColumnAlign.End
            else -> MarkdownColumnAlign.Start
        }
    }
}

private fun String.removeIndent(max: Int): String {
    var index = 0
    while (index < length && index < max && this[index] == ' ') index += 1
    return substring(index)
}

internal fun List<MarkdownBlock>.revealKeys(): Set<RevealBlockKey> = buildSet {
    fun collect(block: MarkdownBlock) {
        when (block) {
            is MarkdownTextBlock -> if (block.text.isNotEmpty()) add(block.revealKey)
            is MarkdownMath -> if (block.rawSource.isNotEmpty()) add(block.revealKey)
            is MarkdownQuote -> block.blocks.forEach(::collect)
            is MarkdownAlert -> block.blocks.forEach(::collect)
            is MarkdownList -> block.items.forEach { item -> item.blocks.forEach(::collect) }
            is MarkdownTable -> (block.header + block.rows.flatten()).forEach(::collect)
            is MarkdownRule -> Unit
        }
    }
    this@revealKeys.forEach(::collect)
}

/** 列表项 marker 的显示时机：项内按源码顺序第一个有文字或公式的块开始显现时。 */
private fun List<MarkdownBlock>.firstRevealKey(): RevealBlockKey? = asSequence()
    .mapNotNull { block ->
        when (block) {
            is MarkdownTextBlock -> block.revealKey.takeIf { block.text.isNotEmpty() }
            is MarkdownMath -> block.revealKey.takeIf { block.rawSource.isNotEmpty() }
            is MarkdownQuote -> block.blocks.firstRevealKey()
            is MarkdownAlert -> block.blocks.firstRevealKey()
            is MarkdownList -> block.items.firstOrNull()?.markerRevealKey
            is MarkdownTable -> block.header.firstOrNull { it.text.isNotEmpty() }?.revealKey
            is MarkdownRule -> null
        }
    }
    .firstOrNull()
