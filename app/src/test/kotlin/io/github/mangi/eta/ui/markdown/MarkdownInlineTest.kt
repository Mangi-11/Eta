package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.mangi.eta.ui.components.EtaMonospace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内渲染的聊天场景宽松规则：模型常写 `**加粗。**接下文`，按 CommonMark flanking
 * 规则不构成强调（解析器留下落单分隔符），渲染层按书写意图宽松配对。
 */
class MarkdownInlineTest {

    @Test
    fun boldClosingBetweenCjkPunctuationAndTextIsPairedByIntent() {
        val paragraph = paragraphOf("**这不代表你的真机。**但你研究")

        assertEquals("这不代表你的真机。但你研究", paragraph.text.text)
        assertTrue(paragraph.text.hasSpanCovering("这不代表你的真机。") { it.fontWeight == FontWeight.Bold })
        assertFalse(paragraph.text.hasSpanCovering("但你研究") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldFollowedImmediatelyByCjkTextIsPairedByIntent() {
        val paragraph = paragraphOf("**我建议你先别把假期变成“大厂题库全背一遍”。**先准备好：")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("我建议你先别把假期变成“大厂题库全背一遍”。") { it.fontWeight == FontWeight.Bold })
        assertFalse(paragraph.text.hasSpanCovering("先准备好") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldAroundInlineCodeInCjkSentenceIsPairedByIntent() {
        val paragraph = paragraphOf("发现 **Android 17 的 `mmd`（内存管理守护程序）**值得你了解")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("内存管理守护程序") { it.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.text.hasSpanCovering("mmd") { it.fontFamily == EtaMonospace })
    }

    @Test
    fun unmatchedDelimiterStaysLiteral() {
        val paragraph = paragraphOf("未闭合 **标记")

        assertEquals("未闭合 **标记", paragraph.text.text)
        assertFalse(paragraph.text.hasSpanCovering("标记") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun spacedAsterisksStayLiteral() {
        val paragraph = paragraphOf("2 * 3 * 4")

        assertEquals("2 * 3 * 4", paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.isEmpty())
    }

    @Test
    fun looseSingleStarPairsAsItalic() {
        val paragraph = paragraphOf("这是 *斜体* 内容")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("斜体") { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun looseTildePairsAsStrikethrough() {
        val paragraph = paragraphOf("这是 ~~删除线~~ 内容")

        assertFalse(paragraph.text.text.contains('~'))
        assertTrue(paragraph.text.hasSpanCovering("删除线") { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun parserStrongKeepsWorkingWithoutLoosePairing() {
        val paragraph = paragraphOf("普通 **加粗** 普通")

        assertEquals("普通 加粗 普通", paragraph.text.text)
        assertTrue(paragraph.text.hasSpanCovering("加粗") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun nestedLooseEmphasisPairsIndependently() {
        val paragraph = paragraphOf("**加粗 *斜体* 结束**接")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("结束") { it.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.text.hasSpanCovering("斜体") { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun streamingPrefixAndFinalTextRenderTheSameBold() {
        val partial = paragraphOf("**它。", isComplete = false)
        val complete = paragraphOf("**它。**但", isComplete = true)

        assertTrue(partial.text.hasSpanCovering("它。") { it.fontWeight == FontWeight.Bold })
        assertEquals("它。但", complete.text.text)
        assertTrue(complete.text.hasSpanCovering("它。") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun kbdTagRendersContentAsInlineCode() {
        val paragraph = paragraphOf("按 <kbd>Ctrl</kbd> 继续")

        assertFalse(paragraph.text.text.contains('<'))
        assertTrue(paragraph.text.text.contains("Ctrl"))
        assertTrue(paragraph.text.hasSpanCovering("Ctrl") { it.fontFamily == EtaMonospace })
    }

    private fun paragraphOf(source: String, isComplete: Boolean = true): MarkdownParagraph =
        StreamingGfmParserSession().parse(source, isComplete = isComplete)
            .document.blocks.filterIsInstance<MarkdownParagraph>().single()

    private fun AnnotatedString.hasSpanCovering(
        substring: String,
        predicate: (SpanStyle) -> Boolean,
    ): Boolean {
        val start = text.indexOf(substring)
        if (start < 0) return false
        val end = start + substring.length
        return spanStyles.any { range ->
            range.start <= start && range.end >= end && predicate(range.item)
        }
    }
}
