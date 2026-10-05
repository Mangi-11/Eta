package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #101 PR-A 階段2：等長方案 + 數學感知投影的回歸鎖定。
 *
 * 與 PR #122 的差異：未閉合單 `$` / `$$` 不延伸、孤立反引號不吞後續公式、
 * `\[...\]` 需獨占一行（由 TexMathDelimiters 保證，此處只鎖 `$` 區間行為）。
 */
class TexMathRangesTest {
    @Test
    fun currencyDollarWithDigitIsNotMath() {
        assertTrue(mathRanges("\$5").isEmpty())
        assertTrue(mathRanges("價格 \$4 和 \$20 每百萬").isEmpty())
    }

    @Test
    fun currencyDoesNotPairWithLaterFormula() {
        // "$5 and $x$"：首個 $5 是貨幣，不與後段 $x$ 配對成整段 MATH。
        val ranges = mathRanges("\$5 and \$x\$")
        assertEquals(1, ranges.size)
        assertEquals("\$x\$", "\$5 and \$x\$".substring(ranges.single().first, ranges.single().last + 1))
    }

    @Test
    fun dollarOpenerRejectsWhitespace() {
        assertTrue(mathRanges("\$ 5").isEmpty())
        assertTrue(mathRanges("\$ a\$").isEmpty())
    }

    @Test
    fun dollarCloserAllowsTrailingText() {
        // Closer 只看 prev（"$x$ 是" 合法），不檢查 next。
        assertEquals(1, mathRanges("\$x\$ 是").size)
    }

    @Test
    fun midPipeIsMathRange() {
        val source = "\$\\mid\$"
        val ranges = mathRanges(source)
        assertEquals(1, ranges.size)
        assertEquals(source, source.substring(ranges.single().first, ranges.single().last + 1))
    }

    @Test
    fun pipeInsideMathDoesNotTriggerTableBuffering() {
        val session = StreamingGfmParserSession()
        // 流式：公式內的 | 不能被當表格緩衝截斷。
        val snapshot = session.parse("公式 \$a|b\$ 結束", isComplete = false)
        assertTrue(snapshot.renderedSource.contains("a|b"))
    }

    @Test
    fun bracketsInsideMathAreNotTruncatedAsLink() {
        val session = StreamingGfmParserSession()
        val snapshot = session.parse("公式 \$a[0,1]\$ 結束", isComplete = false)
        assertTrue(snapshot.renderedSource.contains("[0,1]"))
    }

    @Test
    fun intrawordUnderscoreInFormulaIsKept() {
        val paragraph = StreamingGfmParserSession()
            .parse("行內 \\(a_i+b_j\\) 結束", isComplete = true)
            .document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.text.contains("a_i+b_j"))
    }

    @Test
    fun codeSpanProtectsDollar() {
        assertTrue(mathRanges("`\$x\$` 是行內代碼").isEmpty())
    }

    @Test
    fun unmatchedBacktickDoesNotHideLaterFormula() {
        // 孤立反引號是普通字符，後續 \(x\) 照常歸一為 $$x$$ 並被判為 math。
        val normalized = TexMathDelimiters.normalize("單個 ` 反引號，然後 \\(x\\)")
        assertEquals("單個 ` 反引號，然後 \$\$x\$\$", normalized)
        assertEquals(1, mathRanges(normalized).size)
    }

    @Test
    fun unclosedDoubleDollarDoesNotExtend() {
        // 等長方案：未閉合 $$ 不延伸到段末（與 TexMathDelimiters 未閉合不成 MATH 一致）。
        assertTrue(mathRanges("\$\$5").isEmpty())
        assertTrue(mathRanges("價格 \$\$5 塊").isEmpty())
    }

    @Test
    fun escapedDollarIsNotMath() {
        assertTrue(mathRanges("\\\$x\$").isEmpty())
    }

    @Test
    fun currencyParagraphHasNoMonospaceSpan() {
        val paragraph = StreamingGfmParserSession()
            .parse("價格 \$4 和 \$20 每百萬", isComplete = true)
            .document.blocks.single() as MarkdownParagraph
        assertFalse(paragraph.text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }
}
