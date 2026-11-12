package io.github.mangi.eta.agent.voice

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

internal object SpeechText {
    fun readable(markdown: String): String {
        val root = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
        fun StringBuilder.appendNode(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.CODE_FENCE,
                MarkdownElementTypes.CODE_BLOCK,
                GFMElementTypes.TABLE,
                MarkdownElementTypes.HTML_BLOCK,
                MarkdownTokenTypes.HORIZONTAL_RULE -> append('\n')
                MarkdownElementTypes.ORDERED_LIST -> {
                    val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
                    val marker = items.firstOrNull()?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                    val start = marker?.let { markdown.substring(it.startOffset, it.endOffset).trim().dropLast(1).toIntOrNull() } ?: 1
                    for ((index, item) in items.withIndex()) {
                        append("\n第 ${start + index} 项：")
                        appendNode(item)
                    }
                    append('\n')
                }
                GFMTokenTypes.CHECK_BOX -> {
                    val marker = markdown.substring(node.startOffset, node.endOffset)
                    append(if ('x' in marker || 'X' in marker) "已完成：" else "未完成：")
                }
                MarkdownElementTypes.IMAGE -> append(' ')
                MarkdownElementTypes.LINK_DEFINITION,
                MarkdownTokenTypes.HTML_TAG,
                MarkdownTokenTypes.ATX_HEADER,
                MarkdownTokenTypes.SETEXT_1,
                MarkdownTokenTypes.SETEXT_2,
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.LIST_NUMBER,
                MarkdownTokenTypes.BLOCK_QUOTE,
                MarkdownTokenTypes.BACKTICK,
                MarkdownTokenTypes.EMPH,
                GFMTokenTypes.TILDE -> Unit
                MarkdownElementTypes.INLINE_LINK,
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                        ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                    val text = buildString {
                        label?.children?.filterNot { it.type == MarkdownTokenTypes.LBRACKET ||
                            it.type == MarkdownTokenTypes.RBRACKET }?.forEach { appendNode(it) }
                    }
                    // 先处理链接内部，避免去掉 Markdown 边界后网址吞并相邻正文。
                    append(withoutUrls(text).ifBlank { " " })
                }
                MarkdownElementTypes.AUTOLINK -> append(withoutUrls(
                    markdown.substring(node.startOffset, node.endOffset).removeSurrounding("<", ">"),
                ))
                GFMTokenTypes.GFM_AUTOLINK -> append(withoutUrls(markdown.substring(node.startOffset, node.endOffset)))
                else -> if (node.children.isEmpty()) append(markdown, node.startOffset, node.endOffset)
                    else node.children.forEach { appendNode(it) }
            }
        }
        // 普通文本和行内代码也可能含有未被识别为链接的网址。
        return withoutUrls(buildString { appendNode(root) })
            .lineSequence().map { it.replace(HORIZONTAL_SPACE, " ").trim() }.joinToString("\n")
            .replace(BLANK_LINES, "\n\n").trim()
    }

    // 自动链接节点可能包含网址后的中文正文，只过滤到明确的文本分隔处。
    private fun withoutUrls(text: String): String = URL.replace(text) { match ->
        " " + match.value.takeLastWhile { it in ".,!?;:" }
    }

    private val URL = Regex(
        """(?<![A-Za-z0-9_])(?:https?://|www\.)(?:(?![,;]\p{IsHan})[^\s<>"'`，。！？；：、（）【】《》“”‘’])+""",
        RegexOption.IGNORE_CASE,
    )
    private val HORIZONTAL_SPACE = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    fun chunks(text: String, limit: Int = 500): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            var end = minOf(limit, remaining.length)
            if (end < remaining.length) {
                val boundary = remaining.take(end).indexOfLast { it in "。！？.!?；;\n" }
                if (boundary >= end / 2) end = boundary + 1
                if (remaining[end - 1].isHighSurrogate()) end--
            }
            result += remaining.substring(0, end)
            remaining = remaining.substring(end).trimStart()
        }
        return result
    }
}
