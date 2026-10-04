package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #101：LaTeX 永不消失，只允許變樣式。
 * \(...\) / \[...\] 歸一為 $ / $$ 後進入 MATH 兜底（行內代碼 / LaTeX 代碼塊），
 * 流式投影不得截斷公式內的 `|` / `[`。
 */
class LatexMathTest {

    @Test
    fun inlineDollarMathKeepsContent() {
        val p = paragraphOf("解為 \$x^2 + y = 1\$ 結束")
        assertTrue(p.text.text.contains("x^2 + y = 1"))
    }

    @Test
    fun blockDollarMathBecomesMathCodeBlock() {
        val doc = StreamingGfmParserSession().parse("\$\$\n\\frac{a}{b} = c\n\$\$", isComplete = true).document
        val code = doc.blocks.filterIsInstance<MarkdownCode>().single()
        assertTrue(code.isMath)
        assertTrue(code.text.text.contains("\\frac{a}{b}"))
    }

    @Test
    fun backslashParenMathKeepsBackslashContent() {
        // \(x^2 + \frac{a}{b}\) → $x^2 + \frac{a}{b}$ → MATH 行內代碼，原文不丟。
        val p = paragraphOf("解為 \\(x^2 + \\frac{a}{b}\\) 結束")
        val text = p.text.text
        assertTrue("應保留公式內容，實際：$text", text.contains("x^2"))
        assertTrue("應保留 \\frac，實際：$text", text.contains("\\frac{a}{b}"))
        assertTrue("不應殘留解碼後的裸括號吞掉反斜杠前的內容，實際：$text", text.contains("解為"))
    }

    @Test
    fun backslashBracketMathBecomesMathCodeBlock() {
        val doc = StreamingGfmParserSession().parse("\\[\nE = mc^2\n\\]", isComplete = true).document
        val code = doc.blocks.filterIsInstance<MarkdownCode>().single()
        assertTrue(code.isMath)
        assertTrue(code.text.text.contains("E = mc^2"))
    }

    @Test
    fun mathWithPipeIsNotBufferedAsTable() {
        val source = "\$a | b\$"
        val projected = StreamingGfmProjection.project(source, isComplete = false)
        assertTrue("含 | 的公式不應被表格緩衝清空，實際：'$projected'", projected.isNotEmpty())
        val doc = StreamingGfmParserSession().parse(source, isComplete = false).document
        assertTrue(doc.blocks.isNotEmpty())
    }

    @Test
    fun unclosedInlineMathDoesNotSwallowNextParagraph() {
        // R1-R2 回歸：首段未閉合 \( 虛擬閉合止於段末，次段已閉合公式仍歸一化。
        val source = "解為 \\(x^2 + a\n\n次段 \\(y^2\\) 結束"
        val snapshot = StreamingGfmParserSession().parse(source, isComplete = false)
        val texts = snapshot.document.blocks.filterIsInstance<MarkdownParagraph>().map { it.text.text }
        assertTrue("次段公式應保留，實際：$texts", texts.any { it.contains("y^2") })
        // 行內 \( 不跨段錯配：兩段不應合併成一個 MATH。
        val complete = StreamingGfmParserSession().parse("首段 \\(a\n\n尾段 b\\)", isComplete = true)
        assertTrue(complete.document.blocks.size >= 1)
    }

    @Test
    fun unclosedBlockMathDoesNotHideNextParagraphMath() {
        val source = "\$\$x\n\n次段 \$y\$ 結束"
        val ranges = mathRanges(source)
        assertTrue("次段 \$y\$ 應仍被識別為數學，實際：$ranges", ranges.size >= 2)
    }

    @Test
    fun mathWithBracketsIsNotTruncatedAsLink() {
        val source = "區間 \$a[0,1]\$ 結束"
        val snapshot = StreamingGfmParserSession().parse(source, isComplete = false)
        assertTrue(
            "含 [] 的公式不應被連結截斷，實際：'${snapshot.renderedSource}'",
            snapshot.renderedSource.contains("[0,1]"),
        )
    }

    @Test
    fun streamingUnclosedBackslashParenDoesNotLoseBackslash() {
        val snapshot = StreamingGfmParserSession().parse("解為 \\(x^2 + a", isComplete = false)
        val text = snapshot.document.blocks.filterIsInstance<MarkdownParagraph>()
            .single().text.text
        assertTrue("流式未閉合公式應虛擬閉合成 MATH 而保留內容，實際：$text", text.contains("x^2"))
    }

    @Test
    fun codeFenceLatexDelimitersAreNotConverted() {
        val source = "```latex\n\\(x^2\\)\n```"
        val normalized = LatexMathPreprocessor.normalize(source, isComplete = true)
        assertTrue(normalized.contains("\\(x^2\\)"))
    }

    @Test
    fun intrawordUnderscoreStaysLiteral() {
        val p = paragraphOf("變量 a_i 和 b_j 說明")
        assertTrue("詞內下劃線不應被配對吞掉，實際：${p.text.text}", p.text.text.contains("a_i"))
    }

    @Test
    fun currencyDollarsAreNotMathAndDoNotSwallowTail() {
        // BLOCKER-1 回歸：貨幣 $ 不應延伸到文末污染表格/連結緩衝。
        assertTrue(mathRanges("價格 \$5 和 \$100 結束").isEmpty())
        assertTrue(mathRanges("I have \$5 today").isEmpty())
        val projected = StreamingGfmProjection.project("| \$5 | \$100 |", isComplete = false)
        assertTrue("貨幣表格行仍應被緩衝（空投影），實際：'$projected'", projected.isEmpty())
        val p = paragraphOf("價格 \$5 和 \$100 結束")
        assertTrue(p.text.text.contains("\$5"))
    }

    @Test
    fun dollarCloserAllowsTrailingSpace() {
        // BLOCKER-2 回歸："$x$ 是" 必須閉合；"$ a$" 不成公式；"$a $" 不閉合。
        assertTrue(mathRanges("\$x\$ 是").isNotEmpty())
        val p = paragraphOf("解為 \$x\$ 是")
        assertTrue(p.text.text.contains("x"))
        assertTrue(mathRanges("\$ a\$").isEmpty())
    }

    @Test
    fun bareFenceProtectsLatexDelimiters() {
        // BLOCKER-3 回歸：裸 ``` 圍欄內不轉換。
        val source = "```\n\\(x^2\\)\n```"
        val normalized = LatexMathPreprocessor.normalize(source, isComplete = true)
        assertTrue(normalized.contains("\\(x^2\\)"))
        val tilde = "~~~\n\\[a\\]\n~~~"
        assertTrue(LatexMathPreprocessor.normalize(tilde, isComplete = true).contains("\\[a\\]"))
    }

    @Test
    fun inlineCodeProtectsLatexDelimiters() {
        val source = "執行 `\\(x\\)` 結束"
        val normalized = LatexMathPreprocessor.normalize(source, isComplete = true)
        assertTrue(normalized.contains("\\(x\\)"))
    }

    @Test
    fun unclosedInlineCodeDollarIsNotMath() {
        // MAJOR-3 回歸：未閉合行內代碼內的 $ 不當數學。
        assertTrue(mathRanges("執行 `adb \$5").isEmpty())
    }

    private fun paragraphOf(source: String, isComplete: Boolean = true): MarkdownParagraph =
        StreamingGfmParserSession().parse(source, isComplete = isComplete)
            .document.blocks.filterIsInstance<MarkdownParagraph>().single()
}
