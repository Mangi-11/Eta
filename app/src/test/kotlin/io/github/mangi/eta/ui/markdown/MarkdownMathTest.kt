package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MarkdownMathTest {
    private val textMeasurer by lazy {
        val context = RuntimeEnvironment.getApplication()
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(3f, fontScale = 1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
            defaultLocaleList = LocaleList(context.resources.configuration.locales.toLanguageTags()),
        )
    }

    private fun testCache(counter: AtomicInteger? = null) = MarkdownMathLayoutCache(
        engine = { latex, displayMode ->
            counter?.incrementAndGet()
            if ("\\bad" in latex) {
                null
            } else {
                DisplayList(
                    width = (latex.length * 0.55).coerceAtLeast(1.0),
                    height = if (displayMode) 1.25 else 0.82,
                    depth = if (displayMode) 0.55 else 0.34,
                    items = listOf(
                        DisplayItem.GlyphPath(
                            x = 0.0,
                            y = 0.0,
                            scale = 1.0,
                            font = "Math-Italic",
                            charCode = 'x'.code,
                            color = RaTeXColor(0f, 0f, 0f, 0f),
                        ),
                        DisplayItem.Line(
                            x = 0.0,
                            y = -0.25,
                            width = 1.0,
                            thickness = 0.04,
                            color = RaTeXColor(1f, 0.2f, 0.2f, 1f),
                        ),
                    ),
                )
            }
        }
    )

    @Test
    fun cjkAdjacentInlineMathRecognizesDelimitersAndAssignsBreathingKerning() {
        val session = StreamingGfmParserSession(testCache())
        val source = "设\$x > 0\$，且（\$y = 1\$），满足\\(z_i = 2\\)时成立。"
        val paragraph = session.parse(source, isComplete = true)
            .document.blocks.single() as MarkdownParagraph

        assertEquals("设\$x > 0\$，且（\$y = 1\$），满足\\(z_i = 2\\)时成立。", paragraph.text.text)
        assertEquals(3, paragraph.inlineMath.size)

        val first = paragraph.inlineMath[0]
        assertEquals("x > 0", first.latex)
        assertEquals("\$x > 0\$", first.rawSource)
        assertTrue(first.leadingSpaceEm > 0f)
        // 右侧紧邻全角逗号，标点自身带半宽字怀，不叠加额外空白。
        assertEquals(0f, first.trailingSpaceEm, 0.0001f)

        val second = paragraph.inlineMath[1]
        assertEquals("y = 1", second.latex)
        assertEquals(0f, second.leadingSpaceEm, 0.0001f)
        assertEquals(0f, second.trailingSpaceEm, 0.0001f)

        val third = paragraph.inlineMath[2]
        assertEquals("z_i = 2", third.latex)
        assertEquals("\\(z_i = 2\\)", third.rawSource)
        assertTrue(third.leadingSpaceEm > 0f)
        assertTrue(third.trailingSpaceEm > 0f)
    }

    @Test
    fun currencyRangesAndIsolatedDollarDoNotSwallowSubsequentFormula() {
        val session = StreamingGfmParserSession(testCache())
        listOf(
            "价格 \$4 和 \$20 每百万 token",
            "成本区间 \$4-\$20 每月",
            "赚了\$100，花了\$200",
            "转义 \\\$x + y\\\$ 不是公式",
        ).forEach { plain ->
            val paragraph = session.parse(plain, isComplete = true)
                .document.blocks.single() as MarkdownParagraph
            assertTrue("Expected no inline math in: $plain", paragraph.inlineMath.isEmpty())
        }

        val mixed = session.parse("单价 \$4，当 \$n \\ge 10\$ 时打折", isComplete = true)
            .document.blocks.single() as MarkdownParagraph
        assertEquals(1, mixed.inlineMath.size)
        assertEquals("n \\ge 10", mixed.inlineMath.single().latex)
        assertEquals("单价 \$4，当 \$n \\ge 10\$ 时打折", mixed.text.text)
    }

    @Test
    fun displayMathInterruptsParagraphAndProtectsInternalMarkdownSyntax() {
        val session = StreamingGfmParserSession(testCache())
        val source = """
            推导如下：
            $$
            \begin{aligned}
            x &= a + b \\
            - c

            = d
            \end{aligned}
            $$
            证毕。
        """.trimIndent()

        val blocks = session.parse(source, isComplete = true).document.blocks
        assertEquals(3, blocks.size)
        assertEquals("推导如下：", (blocks[0] as MarkdownParagraph).text.text)
        val math = blocks[1] as MarkdownMath
        assertTrue(math.latex.contains("- c"))
        assertTrue(math.latex.contains("= d"))
        assertNotNull(math.displayList)
        assertEquals("证毕。", (blocks[2] as MarkdownParagraph).text.text)
    }

    @Test
    fun blockQuoteAndListDisplayMathStripOuterContainerMarkers() {
        val session = StreamingGfmParserSession(testCache())
        val quoted = session.parse("> 定理：\n> $$\n> a + b = c\n> $$", isComplete = true)
            .document.blocks.single() as MarkdownQuote
        val quotedMath = quoted.blocks.last() as MarkdownMath
        assertEquals("a + b = c", quotedMath.latex)
        assertFalse(quotedMath.rawSource.contains(">"))

        val list = session.parse("1. 第一步：\n   \\[\n   x^2 + 1 = 0\n   \\]", isComplete = true)
            .document.blocks.single() as MarkdownList
        val listMath = list.items.single().blocks.last() as MarkdownMath
        assertEquals("x^2 + 1 = 0", listMath.latex)
        assertTrue(listMath.rawSource.startsWith("\\["))
        assertTrue(listMath.rawSource.endsWith("\\]"))
    }

    @Test
    fun streamingWaitsForClosingDelimiterAndReusesCachedLayouts() {
        val engineCalls = AtomicInteger(0)
        val cache = testCache(engineCalls)
        val session = StreamingGfmParserSession(cache)

        // 行内半开区间 $[0, 1)$ 不得被流式未闭合链接检测截断。
        val halfOpen = session.parse("在区间 \$[0, 1)\$ 上继续输出", isComplete = false)
        val halfOpenParagraph = halfOpen.document.blocks.single() as MarkdownParagraph
        assertEquals("在区间 \$[0, 1)\$ 上继续输出", halfOpenParagraph.text.text)
        assertEquals("[0, 1)", halfOpenParagraph.inlineMath.single().latex)

        // 未闭合多行块内含竖线 `|A|` 时，不得当作未确认表格隐藏。
        val unclosedDeterminant = session.parse("\$\$\n|A| = ad - bc", isComplete = false)
        assertTrue(unclosedDeterminant.renderedSource.contains("|A| = ad - bc"))
        assertTrue(unclosedDeterminant.document.blocks.none { it is MarkdownMath })

        // 闭合后再排版，且后续段落追加时复用同一 MarkdownMath 实例与引擎缓存。
        engineCalls.set(0)
        val closedBlock = session.parse("\$\$\n|A| = ad - bc\n\$\$", isComplete = false)
        val firstMathInstance = closedBlock.document.blocks.single() as MarkdownMath
        assertEquals(1, engineCalls.get())

        val withNextParagraph = session.parse(
            "\$\$\n|A| = ad - bc\n\$\$\n\n其中 \$a\$ 为首项，继续补充说明",
            isComplete = false,
        )
        assertSame(firstMathInstance, withNextParagraph.document.blocks.first())
        assertEquals(2, engineCalls.get())

        // 同一末段继续追加文字时，段首已闭合的 `$a$` 直接命中公式布局缓存，不重复调用引擎。
        session.parse(
            "\$\$\n|A| = ad - bc\n\$\$\n\n其中 \$a\$ 为首项，继续补充说明更多文字",
            isComplete = false,
        )
        assertEquals(2, engineCalls.get())
    }

    @Test
    fun bindDefaultColorReplacesSentinelAndPreservesExplicitFormulaColors() {
        val targetColor = Color(red = 0.9f, green = 0.85f, blue = 0.8f, alpha = 0.6f)
        val dl = testCache().layout("x", displayMode = false)!!
        val bound = dl.bindDefaultColor(targetColor)
        val glyph = bound.items[0] as DisplayItem.GlyphPath
        val explicitLine = bound.items[1] as DisplayItem.Line

        assertEquals(targetColor.red, glyph.color.r, 0.0001f)
        assertEquals(targetColor.green, glyph.color.g, 0.0001f)
        assertEquals(targetColor.blue, glyph.color.b, 0.0001f)
        assertEquals(targetColor.alpha, glyph.color.a, 0.0001f)
        assertEquals(1f, explicitLine.color.r, 0.0001f)
        assertEquals(0.2f, explicitLine.color.g, 0.0001f)
        assertEquals(targetColor.alpha, explicitLine.color.a, 0.0001f)
    }

    @Test
    fun boldAndTableCellsPreserveInlineMathAndSpanColors() {
        val style = MarkdownInlineStyle(
            linkColor = Color(0xFF3482FF),
            codeBackground = Color(0x1F808080),
            strongColor = Color(0xFF112233),
        )
        val session = StreamingGfmParserSession(testCache())
        val doc = session.parse(
            "**重点结论：\$E = mc^2\$。**下文\n\n| 指标 | 公式 |\n| --- | --- |\n| 复杂度 | \$O(n \\log n)\$ |",
            isComplete = true,
            style = style,
        ).document

        val paragraph = doc.blocks[0] as MarkdownParagraph
        val spec = paragraph.inlineMath.single()
        assertEquals("E = mc^2", spec.latex)
        assertTrue(paragraph.text.spanStyles.any {
            it.start <= spec.rangeStart && it.end >= spec.rangeEnd && it.item.color == style.strongColor
        })

        val table = doc.blocks[1] as MarkdownTable
        val formulaCell = table.rows.single()[1]
        assertEquals("\$O(n \\log n)\$", formulaCell.text.text)
        assertEquals("O(n \\log n)", formulaCell.inlineMath.single().latex)
    }

    @Test
    fun inlineMathBoxMetricsKeepExactBaselineAlignmentAndAdaptToNarrowWidths() {
        // 验证不同字高与上下不对称公式下的零基线误差与不裁切保证。
        listOf(
            Triple(0.85, 0.35, -42 to 11),
            Triple(1.40, 0.05, -44 to 12),
            Triple(0.45, 0.95, -36 to 10),
        ).forEach { (heightEm, depthEm, fontMetrics) ->
            val metrics = computeInlineMathBoxMetrics(
                displayListWidthEm = 2.4,
                displayListHeightEm = heightEm,
                displayListDepthEm = depthEm,
                hasItems = true,
                textFontSizePx = 48f,
                fontAscentPx = fontMetrics.first,
                fontDescentPx = fontMetrics.second,
                leadingSpaceEm = 0.11f,
                trailingSpaceEm = 0.11f,
                maxAvailableWidthPx = 900,
            )
            val heightAbovePx = (heightEm * metrics.mathFontSizePx).toFloat() + 1f
            val depthBelowPx = (depthEm * metrics.mathFontSizePx).toFloat() + 1f
            val topFromBaseline = (fontMetrics.first + fontMetrics.second - metrics.boxHeightPx) / 2
            val renderedBaselineFromLineBaseline = topFromBaseline + metrics.drawTopPx + heightAbovePx

            assertEquals(0f, renderedBaselineFromLineBaseline, 0.01f)
            assertTrue(metrics.drawTopPx >= 0f)
            assertTrue(metrics.drawTopPx + heightAbovePx + depthBelowPx <= metrics.boxHeightPx)
            assertFalse(metrics.scrollable)
        }

        // 窄屏略超宽：温和微缩适配单行，不开启内部滚动。
        val gentlyScaled = computeInlineMathBoxMetrics(
            displayListWidthEm = 5.0,
            displayListHeightEm = 0.8,
            displayListDepthEm = 0.2,
            hasItems = true,
            textFontSizePx = 48f,
            fontAscentPx = -42,
            fontDescentPx = 11,
            leadingSpaceEm = 0f,
            trailingSpaceEm = 0f,
            maxAvailableWidthPx = 220,
        )
        assertFalse(gentlyScaled.scrollable)
        assertTrue(gentlyScaled.boxWidthPx <= 220)

        // 窄屏超长行内公式：锁定最大占位宽度并启用内部横向滚动。
        val scrollableLong = computeInlineMathBoxMetrics(
            displayListWidthEm = 20.0,
            displayListHeightEm = 0.8,
            displayListDepthEm = 0.2,
            hasItems = true,
            textFontSizePx = 48f,
            fontAscentPx = -42,
            fontDescentPx = 11,
            leadingSpaceEm = 0f,
            trailingSpaceEm = 0f,
            maxAvailableWidthPx = 300,
        )
        assertTrue(scrollableLong.scrollable)
        assertEquals(300, scrollableLong.boxWidthPx)
        assertTrue(scrollableLong.contentWidthPx > scrollableLong.boxWidthPx)
    }

    @Test
    fun placeholderMeasurementPreservesLatexSourceAndDoesNotTruncateAcrossLineWraps() {
        val session = StreamingGfmParserSession(testCache())
        val paragraph = session.parse(
            "这是一段较长的中文导语用于触发自动换行后紧跟行内公式 \$a_1 + b_1 = c_1\$ 再接下文。",
            isComplete = true,
        ).document.blocks.single() as MarkdownParagraph

        val spec = paragraph.inlineMath.single()
        val placeholders = listOf(
            androidx.compose.ui.text.AnnotatedString.Range(
                item = Placeholder(
                    width = 64.sp,
                    height = 24.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
                start = spec.rangeStart,
                end = spec.rangeEnd,
            )
        )
        val layout = textMeasurer.measure(
            text = paragraph.text,
            style = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            placeholders = placeholders,
            constraints = Constraints(maxWidth = 480),
        )

        assertNotNull("Wrapped inline math placeholder must not be truncated to null", layout.placeholderRects.single())
        assertEquals(
            "\$a_1 + b_1 = c_1\$",
            layout.layoutInput.text.text.substring(spec.rangeStart, spec.rangeEnd),
        )
        val revealPath = layout.getPathForRange(spec.rangeStart, spec.rangeEnd)
        assertFalse(revealPath.isEmpty)
    }

    @Test
    fun atomicRevealBoundariesKeepFormulaAsSingleStepAndMapProgressMonotonically() {
        val text = "设 \$a_1 + b_1\$ 成立"
        val formulaStart = text.indexOf('$')
        val formulaEnd = text.lastIndexOf('$') + 1
        val spans = listOf(RevealAtomicSpan(formulaStart, formulaEnd))

        assertArrayEquals(
            intArrayOf(0, 1, 2, formulaEnd, formulaEnd + 1, formulaEnd + 2, formulaEnd + 3),
            graphemeBoundariesWithAtomicSpans(text, spans),
        )

        val boundaries = graphemeBoundariesWithAtomicSpans(text, spans)
        // 显现尚未到达公式时，保持原有精确字素进度。
        assertEquals(1.4f, mapProgressAcrossAtomicChange(boundaries, oldCharOffset = 1, oldFraction = 0.4f), 0.001f)
        // 显现在公式闭合前已进入公式内部时，闭合瞬间对齐到该公式结束边界（下标 3），不跳过后续“ 成立”。
        assertEquals(3f, mapProgressAcrossAtomicChange(boundaries, oldCharOffset = 6, oldFraction = 0.7f), 0.001f)
    }
}
