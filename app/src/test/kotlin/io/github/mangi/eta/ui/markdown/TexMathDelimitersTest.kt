package io.github.mangi.eta.ui.markdown

import io.github.mangi.eta.ui.components.EtaMonospace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexMathDelimitersTest {
    @Test
    fun inlineParenthesesKeepLatexSourceIntact() {
        val paragraph = blocks("行内 \\(a_i + b_j = \\frac{1}{2}\\) 结束").single() as MarkdownParagraph

        val formula = "a_i + b_j = \\frac{1}{2}"
        assertTrue(paragraph.text.text.contains(formula))
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontFamily == EtaMonospace &&
                paragraph.text.text.substring(range.start, range.end).contains(formula)
        })
    }

    @Test
    fun displayBracketsBecomeMathBlockWithoutLosingEscapes() {
        val math = blocks("\\[\n\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}\n\\]").single() as MarkdownCode

        assertTrue(math.isMath)
        assertEquals("\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}", math.text.text)
    }

    @Test
    fun singleLineDisplayBracketsAreMath() {
        val math = blocks("推导如下：\n\n\\[ E = mc^2 \\]").last() as MarkdownCode
        assertTrue(math.isMath)
        assertEquals("E = mc^2", math.text.text)
    }

    @Test
    fun normalizationPreservesOffsets() {
        val source = "a \\(x\\) b\n\n\\[\ny\n\\]"
        assertEquals(source.length, TexMathDelimiters.normalize(source).length)
    }

    @Test
    fun escapedMarkdownBracketsAndCodeAreNotMath() {
        listOf(
            "数组 arr\\[0\\] 的值",
            "`\\(x\\)` 是行内代码",
            "```latex\n\\[\nx\n\\]\n```",
            "转义反斜杠 \\\\(x\\\\) 不是公式",
            "未闭合 \\(x 后面没有了",
            "跨空行 \\(x\n\ny\\) 不是公式",
        ).forEach { source ->
            assertEquals(source, TexMathDelimiters.normalize(source))
        }
    }

    @Test(timeout = 1_000)
    fun fencedBlankLinesPreserveCodeAndNormalizeSurroundingMath() {
        listOf("```", "~~~").forEach { fence ->
            listOf("\n", "\r\n").forEach { newline ->
                val code = listOf("\\(a_i\\)", "", "", " ", "\t", "\\[", "b_j", "\\]")
                    .joinToString(newline)
                val fenced = "${fence}latex$newline$code$newline$fence"
                val source = "\\(before\\)$newline$fenced$newline\\[after\\]"
                val expected = "\$\$before\$\$$newline$fenced$newline\$\$after\$\$"

                assertEquals(fenced, TexMathDelimiters.normalize(fenced))
                assertEquals(expected, TexMathDelimiters.normalize(source))
            }
        }
    }

    @Test(timeout = 1_000)
    fun unclosedFencesPreserveBlankLinesThroughEof() {
        listOf("```", "~~~").forEach { fence ->
            listOf("", "\n", "\n\n").forEach { ending ->
                val source = "${fence}latex\n\n\n\\(literal\\)$ending"

                assertEquals(source, TexMathDelimiters.normalize(source))
            }
        }
    }

    @Test(timeout = 1_000)
    fun shortOrMismatchedFencesDoNotExposeCodeToMathNormalization() {
        listOf("````" to "~~~", "~~~~" to "```").forEach { (fence, otherFence) ->
            val code = "${fence}latex\n\n${fence.dropLast(1)}\n\\(a_i\\)\n\n" +
                "$otherFence\n\\[b_j\\]\n\n$fence${fence.first()}"
            val source = "$code\n\\(after\\)"

            assertEquals("$code\n\$\$after\$\$", TexMathDelimiters.normalize(source))
        }
    }

    @Test
    fun unmatchedBacktickDoesNotHideLaterFormula() {
        val normalized = TexMathDelimiters.normalize("单个 ` 反引号，然后 \\(x\\)")
        assertEquals("单个 ` 反引号，然后 \$\$x\$\$", normalized)
    }

    @Test
    fun dollarMathAndCurrencyStayUnchanged() {
        listOf("价格 \$4 和 \$20 每百万", "行内 \$E=mc^2\$ 结束").forEach { source ->
            assertEquals(source, TexMathDelimiters.normalize(source))
        }
        val paragraph = blocks("价格 \$4 和 \$20 每百万").single() as MarkdownParagraph
        assertFalse(paragraph.text.spanStyles.any { it.item.fontFamily == EtaMonospace })
    }

    @Test
    fun streamingUnclosedDisplayMathConvertsOnceClosed() {
        val session = StreamingGfmParserSession()
        val partial = session.parse("\\[\n\\frac{a}{b}", isComplete = false).document.blocks
        assertFalse(partial.any { it is MarkdownCode && it.isMath })
        val complete = session.parse("\\[\n\\frac{a}{b}\n\\]", isComplete = true).document.blocks
        assertEquals("\\frac{a}{b}", (complete.single() as MarkdownCode).text.text)
    }

    @Test(timeout = 5_000)
    fun streamingFencedBlankLinesKeepCodeAndResumeMathAfterClosing() {
        listOf("```", "~~~").forEach { fence ->
            val session = StreamingGfmParserSession()
            val code = "\\(a_i\\)\n\n\n\\[\nb_j\n\\]"
            val openSource = "前 \\(before\\)\n\n${fence}latex\n$code"
            val source = "$openSource\n$fence\n\n\\[after\\]"

            for (end in 0..source.length) {
                val prefix = source.substring(0, end)
                val partial = session.parse(prefix, isComplete = false)
                assertEquals(prefix, partial.originalSource)
                if (end == openSource.length) {
                    val partialCode = partial.document.blocks.filterIsInstance<MarkdownCode>().single()
                    assertFalse(partialCode.isMath)
                    assertEquals(code, partialCode.text.text)
                }
            }

            val complete = session.parse(source, isComplete = true)
            assertEquals(source, complete.originalSource)
            assertEquals(
                "前 \$\$before\$\$\n\n${fence}latex\n$code\n$fence\n\n\$\$after\$\$",
                complete.renderedSource,
            )
            val codeBlocks = complete.document.blocks.filterIsInstance<MarkdownCode>()
            val fencedCode = codeBlocks.single { !it.isMath }
            assertEquals("latex", fencedCode.language)
            assertEquals(code, fencedCode.text.text)
            assertEquals(source.indexOf(fence), fencedCode.offset)
            val math = codeBlocks.single { it.isMath }
            assertEquals("after", math.text.text)
            assertEquals(source.indexOf("\\[after\\]"), math.offset)
        }
    }

    private fun blocks(source: String): List<MarkdownBlock> =
        StreamingGfmParserSession().parse(source, isComplete = true).document.blocks
}
