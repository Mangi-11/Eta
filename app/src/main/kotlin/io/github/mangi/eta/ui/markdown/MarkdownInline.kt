package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * 行内样式里随主题变化的部分。它在后台构建 AnnotatedString 时就要确定，
 * 因此作为解析输入的一部分：主题切换会让缓存失效并重建，而不是在绘制时逐段改色。
 */
@Immutable
internal data class MarkdownInlineStyle(
    val linkColor: Color,
    val codeBackground: Color,
) {
    companion object {
        val Default = MarkdownInlineStyle(linkColor = Color(0xFF3482FF), codeBackground = Color(0x1F808080))
    }
}

/**
 * 把一组行内节点转换为 AnnotatedString。
 *
 * 只处理聊天里实际出现的 GFM 行内语法；未识别的叶子按源码原样输出，确保任何内容
 * 都不会因为缺少渲染分支而凭空消失（数学公式就曾因此整段丢失）。
 */
internal class MarkdownInlineBuilder(
    private val source: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
) {
    fun build(nodes: List<ASTNode>): AnnotatedString {
        val builder = AnnotatedString.Builder()
        val state = LineState()
        builder.appendNodes(nodes, parent = null, state)
        return builder.toAnnotatedString().trimTrailingWhitespace()
    }

    /** 软换行后的缩进与引用前缀（`>`）属于块结构，不属于正文。 */
    private class LineState {
        var atLineStart = true
        /** 硬换行（行尾两空格或反斜杠）后紧跟的 EOL 已经由硬换行输出过。 */
        var afterHardBreak = false
    }

    private fun AnnotatedString.Builder.appendNode(node: ASTNode, parent: ASTNode?, state: LineState) {
        val type = node.type
        if (state.atLineStart && (type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.BLOCK_QUOTE)) {
            return
        }
        if (type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.HARD_LINE_BREAK) {
            // 聊天输出里的单个换行几乎总是作者想要的换行；按 CommonMark 折成空格会
            // 在中文之间插入多余空白，也会让模型刻意分行的内容挤成一段。
            if (!(type == MarkdownTokenTypes.EOL && state.afterHardBreak)) append('\n')
            state.afterHardBreak = type == MarkdownTokenTypes.HARD_LINE_BREAK
            state.atLineStart = true
            return
        }
        state.afterHardBreak = false
        when (type) {
            MarkdownTokenTypes.WHITE_SPACE -> append(' ')
            MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(node, state)
            }
            GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.CODE_SPAN -> appendCode(codeSpanText(node))
            GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> appendCode(mathText(node))
            MarkdownElementTypes.INLINE_LINK -> appendInlineLink(node, state)
            MarkdownElementTypes.FULL_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_TEXT),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_LABEL),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK }?.text() ?: node.text()
                appendUrl(url.removeSurrounding("<", ">"))
            }
            GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK ->
                appendUrl(node.text())
            MarkdownElementTypes.IMAGE -> appendImage(node, state)
            MarkdownTokenTypes.HTML_TAG -> appendHtmlTag(node, state)
            else -> when {
                isDelimiterOf(type, parent) -> Unit
                node.children.isNotEmpty() -> appendChildren(node, state)
                else -> append(decodeMarkdownText(node.text()))
            }
        }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendChildren(node: ASTNode, state: LineState) {
        appendNodes(node.children, parent = node, state)
    }

    /**
     * 按兄弟节点列表输出内容。模型输出里常见 `**加粗。**接下文` 这类写法：按 CommonMark 的
     * flanking 规则，分隔符夹在 CJK 标点与文字之间时不构成强调，解析器会留下落单的
     * EMPH/TILDE 记号。聊天场景按书写意图处理：对同一级兄弟节点做一次宽松配对，只要求
     * 紧贴记号的内容不是空白；配不上对的记号仍按原文输出，不丢字符。
     */
    private fun AnnotatedString.Builder.appendNodes(nodes: List<ASTNode>, parent: ASTNode?, state: LineState) {
        // 已是合法强调的子节点时，记号是真正的分隔符，由 isDelimiterOf 过滤，不参与宽松配对。
        val pairs = if (parent?.type in REAL_EMPHASIS_TYPES) emptyMap() else pairLooseInline(nodes)
        var index = 0
        while (index < nodes.size) {
            val pair = pairs[index]
            if (pair != null) {
                withStyle(pair.style) {
                    if (pair.padSpaces) append(' ')
                    appendNodes(nodes.subList(pair.contentStart, pair.contentEnd), parent, state)
                    if (pair.padSpaces) append(' ')
                }
                index = pair.nextIndex
            } else {
                appendNode(nodes[index], parent, state)
                index += 1
            }
        }
    }

    /** 一对被重新解释为样式的兄弟节点区间；两端记号自身不输出文本。 */
    private class PairedInline(
        val contentStart: Int,
        val contentEnd: Int,
        val nextIndex: Int,
        val style: SpanStyle,
        /** 行内代码风格的底色需要前后空格撑开，不贴着字形。 */
        val padSpaces: Boolean = false,
    )

    private fun pairLooseInline(nodes: List<ASTNode>): Map<Int, PairedInline> =
        pairLooseDelimiters(nodes) + pairKbdTags(nodes)

    private fun pairLooseDelimiters(nodes: List<ASTNode>): Map<Int, PairedInline> {
        data class Run(val start: Int, val char: Char, val length: Int)

        val runs = mutableListOf<Run>()
        var index = 0
        while (index < nodes.size) {
            val char = when (nodes[index].type) {
                MarkdownTokenTypes.EMPH -> '*'
                GFMTokenTypes.TILDE -> '~'
                else -> null
            }
            if (char == null) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < nodes.size && nodes[end + 1].type == nodes[index].type) end += 1
            val length = end - index + 1
            // `*`/`**` 分别配对为斜体/粗体，`~~` 配对为删除线；单个 `~` 常见于路径，不配对。
            val eligible = if (char == '*') length <= 2 else length == 2
            if (eligible) runs += Run(index, char, length)
            index = end + 1
        }

        fun ASTNode?.isBlankLike(): Boolean = when (this?.type) {
            null -> true
            MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> true
            else -> text().isBlank()
        }

        val openers = ArrayDeque<Run>()
        val pairs = mutableMapOf<Int, PairedInline>()
        for (run in runs) {
            // Issue #101：单词内下划线（如 a_i、x_1）不成强调，与流式投影的守卫一致。
            // 解析器的 '*' 與 '_' 都走 EMPH token，這裡按實際文本判斷是否含 '_'。
            // 否則 \(a_i+b_j\) 未能成 MATH 時，下劃線會被配對消費而消失。
            val runText = nodes.subList(run.start, run.start + run.length)
                .joinToString("") { if (it.children.isEmpty()) it.text() else "" }
            if ('_' in runText) {
                val prevChar = nodes.getOrNull(run.start - 1)
                    ?.takeIf { it.children.isEmpty() }?.text()?.lastOrNull()
                val nextChar = nodes.getOrNull(run.start + run.length)
                    ?.takeIf { it.children.isEmpty() }?.text()?.firstOrNull()
                if (prevChar?.isLetterOrDigit() == true && nextChar?.isLetterOrDigit() == true) continue
            }
            val canOpen = !nodes.getOrNull(run.start + run.length).isBlankLike()
            val canClose = !nodes.getOrNull(run.start - 1).isBlankLike()
            val opener = if (canClose) {
                openers.lastOrNull { it.char == run.char && it.length == run.length }
            } else {
                null
            }
            if (opener != null) {
                openers.remove(opener)
                val style = when {
                    run.char == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    run.length == 2 -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pairs[opener.start] = PairedInline(
                    contentStart = opener.start + opener.length,
                    contentEnd = run.start,
                    nextIndex = run.start + run.length,
                    style = style,
                )
            } else if (canOpen) {
                openers.addLast(run)
            }
        }
        return pairs
    }

    /** `<kbd>` 是唯一在聊天输出里常见的行内 HTML：配对后按行内代码样式显示内容。 */
    private fun pairKbdTags(nodes: List<ASTNode>): Map<Int, PairedInline> {
        val open = ArrayDeque<Int>()
        val pairs = mutableMapOf<Int, PairedInline>()
        nodes.forEachIndexed { index, node ->
            if (node.type != MarkdownTokenTypes.HTML_TAG) return@forEachIndexed
            val tag = node.text()
            when {
                KBD_OPEN.matches(tag) -> open.addLast(index)
                KBD_CLOSE.matches(tag) && open.isNotEmpty() -> {
                    val start = open.removeLast()
                    if (start + 1 < index) {
                        pairs[start] = PairedInline(
                            contentStart = start + 1,
                            contentEnd = index,
                            nextIndex = index + 1,
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 0.88.em,
                                background = style.codeBackground,
                            ),
                            padSpaces = true,
                        )
                    }
                }
            }
        }
        return pairs
    }

    private fun AnnotatedString.Builder.appendCode(code: String) {
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.88.em,
                background = style.codeBackground,
            ),
        ) {
            // 前后半角空格让底色不贴着字形，读起来像一个独立的代码片段。
            append(' ')
            append(code)
            append(' ')
        }
    }

    private fun AnnotatedString.Builder.appendInlineLink(node: ASTNode, state: LineState) {
        val textNode = node.child(MarkdownElementTypes.LINK_TEXT)
        val destination = node.child(MarkdownElementTypes.LINK_DESTINATION)?.text()
            ?.removeSurrounding("<", ">")
            ?.let(::decodeMarkdownText)
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendReferenceLink(
        node: ASTNode,
        textNode: ASTNode?,
        labelNode: ASTNode?,
        state: LineState,
    ) {
        val destination = labelNode?.let { links?.getLinkInfo(it.text()) }?.destination?.toString()
        if (destination == null) {
            // 没有对应定义时它只是带方括号的普通文字，原样保留。
            append(decodeMarkdownText(node.text()))
            return
        }
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendLink(
        url: String?,
        textNode: ASTNode?,
        fallback: ASTNode,
        state: LineState,
    ) {
        val safeUrl = url?.let(::safeLinkUrl)
        val label: AnnotatedString.Builder.() -> Unit = {
            if (textNode != null) {
                appendNodes(
                    textNode.children.filterNot {
                        it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET
                    },
                    parent = textNode,
                    state = state,
                )
            } else {
                append(decodeMarkdownText(fallback.text()))
            }
        }
        if (safeUrl == null) {
            label()
        } else {
            withLink(linkAnnotation(safeUrl)) { label() }
        }
    }

    private fun AnnotatedString.Builder.appendUrl(raw: String) {
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val safeUrl = safeLinkUrl(url)
        if (safeUrl == null) append(raw) else withLink(linkAnnotation(safeUrl)) { append(raw) }
    }

    /** 聊天不加载远程图片：以替代文字作为可点开的链接，内容不丢失也不产生网络请求。 */
    private fun AnnotatedString.Builder.appendImage(node: ASTNode, state: LineState) {
        val link = node.child(MarkdownElementTypes.INLINE_LINK)
        val alt = link?.child(MarkdownElementTypes.LINK_TEXT)?.text()?.removeSurrounding("[", "]").orEmpty()
        val destination = link?.child(MarkdownElementTypes.LINK_DESTINATION)?.text()?.removeSurrounding("<", ">")
        val label = alt.ifBlank { destination.orEmpty() }
        val safeUrl = destination?.let(::safeLinkUrl)
        if (safeUrl == null) append(label) else withLink(linkAnnotation(safeUrl)) { append(label) }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendHtmlTag(node: ASTNode, state: LineState) {
        val tag = node.text()
        if (BR_TAG.matches(tag)) {
            append('\n')
            state.atLineStart = true
        } else {
            append(tag)
        }
    }

    private fun linkAnnotation(url: String) = LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(style = SpanStyle(color = style.linkColor, fontWeight = FontWeight.Medium)),
    )

    private fun codeSpanText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return ""
        val inner = source.substring(children.first().endOffset, children.last().startOffset)
            .replace('\n', ' ')
        // CommonMark：两端各有一个空格且内容不全是空格时各去掉一个，用于包裹反引号。
        return if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.isNotBlank()) {
            inner.substring(1, inner.length - 1)
        } else {
            inner
        }
    }

    private fun mathText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim()
    }

    private fun isDelimiterOf(type: IElementType, parent: ASTNode?): Boolean = when (parent?.type) {
        MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH -> type == MarkdownTokenTypes.EMPH
        GFMElementTypes.STRIKETHROUGH -> type == GFMTokenTypes.TILDE
        else -> false
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    private companion object {
        val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val KBD_OPEN = Regex("""<kbd\s*>""", RegexOption.IGNORE_CASE)
        val KBD_CLOSE = Regex("""</kbd\s*>""", RegexOption.IGNORE_CASE)

        /** 这些节点的 EMPH/TILDE 子节点是已被消费的真正分隔符，不参与宽松配对。 */
        val REAL_EMPHASIS_TYPES = setOf(
            MarkdownElementTypes.STRONG,
            MarkdownElementTypes.EMPH,
            GFMElementTypes.STRIKETHROUGH,
        )
    }
}

internal fun ASTNode.child(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

/** 只放行明确安全的协议；其他 scheme 作为普通文字显示，不交给系统打开。 */
internal fun safeLinkUrl(url: String): String? {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { scheme in SAFE_LINK_SCHEMES }
}

private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto")

/**
 * 处理 CommonMark 反斜杠转义与 HTML 实体。解析器把它们保留在 TEXT 叶子里，
 * 需要渲染前还原；库自带的转换器输出的是 HTML 转义文本，不适合直接显示。
 */
internal fun decodeMarkdownText(text: String): String {
    if ('\\' !in text && '&' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
            out.append(text[index + 1])
            index += 2
            continue
        }
        if (char == '&') {
            val end = text.indexOf(';', index)
            val decoded = if (end in index + 2..index + 10) decodeEntity(text.substring(index + 1, end)) else null
            if (decoded != null) {
                out.append(decoded)
                index = end + 1
                continue
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

private fun decodeEntity(name: String): String? = when {
    name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.toCodePointString()
    name.startsWith("#") -> name.substring(1).toIntOrNull()?.toCodePointString()
    else -> NAMED_ENTITIES[name]
}

private fun Int.toCodePointString(): String? =
    takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }

private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "times" to "×",
    "middot" to "·",
)

private fun AnnotatedString.trimTrailingWhitespace(): AnnotatedString {
    var end = length
    while (end > 0 && text[end - 1].isWhitespace()) end -= 1
    return if (end == length) this else subSequence(0, end)
}

/** 构建行内文本时只有这一个入口，保证段落、标题与表格单元格的规则一致。 */
internal fun inlineText(
    nodes: List<ASTNode>,
    source: String,
    links: LinkMap?,
    style: MarkdownInlineStyle,
): AnnotatedString = MarkdownInlineBuilder(source, links, style).build(nodes)
