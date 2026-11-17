package io.github.mangi.eta.ui.markdown

import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexMathDelimitersTest {
    private val fakeMathCache = MarkdownMathLayoutCache(
        engine = { latex, _ ->
            if ("\\invalid" in latex) {
                null
            } else {
                DisplayList(
                    width = 2.0,
                    height = 0.8,
                    depth = 0.2,
                    items = listOf(
                        DisplayItem.Line(
                            x = 0.0,
                            y = 0.0,
                            width = 1.0,
                            thickness = 0.05,
                            color = RaTeXColor.Black,
                        )
                    ),
                )
            }
        }
    )

    @Test
    fun inlineParenthesesKeepLatexSourceIntactAndProduceInlineMath() {
        val paragraph = blocks("行内 \\(a_i + b_j = \\frac{1}{2}\\) 结束").single() as MarkdownParagraph

        val rawFormula = "\\(a_i + b_j = \\frac{1}{2}\\)"
        assertEquals("行内 $rawFormula 结束", paragraph.text.text)
        val mathSpec = paragraph.inlineMath.single()
        assertEquals("a_i + b_j = \\frac{1}{2}", mathSpec.latex)
        assertEquals(rawFormula, mathSpec.rawSource)
    }

    @Test
    fun failedInlineMathPreservesExactOriginalText() {
        val paragraph = blocks("解析失败 \\(\\invalid{x}\\) 保留原文").single() as MarkdownParagraph

        assertEquals("解析失败 \\(\\invalid{x}\\) 保留原文", paragraph.text.text)
        assertTrue(paragraph.inlineMath.isEmpty())
    }

    @Test
    fun displayBracketsBecomeMathBlockWithoutLosingEscapes() {
        val math = blocks("\\[\n\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}\n\\]").single() as MarkdownMath

        assertEquals("\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}", math.latex)
        assertEquals("\\[\n\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}\n\\]", math.rawSource)
        assertNotNull(math.displayList)
    }

    @Test
    fun singleLineDisplayBracketsAreMath() {
        val math = blocks("推导如下：\n\n\\[ E = mc^2 \\]").last() as MarkdownMath
        assertEquals("E = mc^2", math.latex)
        assertEquals("\\[ E = mc^2 \\]", math.rawSource)
        assertNotNull(math.displayList)
    }

    @Test
    fun failedDisplayMathKeepsRawSourceForFallback() {
        val math = blocks("\\[ \\invalid{x} \\]").single() as MarkdownMath
        assertEquals("\\invalid{x}", math.latex)
        assertEquals("\\[ \\invalid{x} \\]", math.rawSource)
        assertNull(math.displayList)
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
        assertTrue(paragraph.inlineMath.isEmpty())
        assertEquals("价格 \$4 和 \$20 每百万", paragraph.text.text)
    }

    @Test
    fun streamingUnclosedDisplayMathConvertsOnceClosed() {
        val session = StreamingGfmParserSession(fakeMathCache)
        val partial = session.parse("\\[\n\\frac{a}{b}", isComplete = false).document.blocks
        assertFalse(partial.any { it is MarkdownMath })
        val complete = session.parse("\\[\n\\frac{a}{b}\n\\]", isComplete = true).document.blocks
        assertEquals("\\frac{a}{b}", (complete.single() as MarkdownMath).latex)
    }

    @Test(timeout = 5_000)
    fun streamingFencedBlankLinesKeepCodeAndResumeMathAfterClosing() {
        listOf("```", "~~~").forEach { fence ->
            val session = StreamingGfmParserSession(fakeMathCache)
            val code = "\\(a_i\\)\n\n\n\\[\nb_j\n\\]"
            val openSource = "前 \\(before\\)\n\n${fence}latex\n$code"
            val source = "$openSource\n$fence\n\n\\[after\\]"

            for (end in 0..source.length) {
                val prefix = source.substring(0, end)
                val partial = session.parse(prefix, isComplete = false)
                assertEquals(prefix, partial.originalSource)
                if (end == openSource.length) {
                    val partialCode = partial.document.blocks.filterIsInstance<MarkdownCode>().single()
                    assertEquals(code, partialCode.text.text)
                    assertTrue(partial.document.blocks.none { it is MarkdownMath })
                }
            }

            val complete = session.parse(source, isComplete = true)
            assertEquals(source, complete.originalSource)
            assertEquals(
                "前 \$\$before\$\$\n\n${fence}latex\n$code\n$fence\n\n\$\$after\$\$",
                complete.renderedSource,
            )
            val fencedCode = complete.document.blocks.filterIsInstance<MarkdownCode>().single()
            assertEquals("latex", fencedCode.language)
            assertEquals(code, fencedCode.text.text)
            assertEquals(source.indexOf(fence), fencedCode.offset)
            val math = complete.document.blocks.filterIsInstance<MarkdownMath>().single()
            assertEquals("after", math.latex)
            assertEquals(source.indexOf("\\[after\\]"), math.offset)
        }
    }

    private fun blocks(source: String): List<MarkdownBlock> =
        StreamingGfmParserSession(fakeMathCache).parse(source, isComplete = true).document.blocks
}
