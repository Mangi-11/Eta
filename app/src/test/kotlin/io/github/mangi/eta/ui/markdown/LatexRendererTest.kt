package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #101 階段2：KaTeX HTML 模板的轉義與渲染開關。 */
class LatexRendererTest {

    @Test
    fun htmlContainsKatexAssetsAndRendersLatex() {
        val html = LatexRenderer.buildKatexHtml("\\frac{a}{b}", displayMode = true, dark = false)
        assertTrue(html.contains("katex.min.js"))
        assertTrue(html.contains("katex.min.css"))
        assertTrue(html.contains("displayMode:true"))
        // S1 安全：禁用 trust，\href{javascript:} 不執行。
        assertTrue(html.contains("trust:false"))
        // fallbacks 保留原文（HTML 轉義後）。
        assertTrue(html.contains("\\frac"))
    }

    @Test
    fun inlineModeSetsDisplayFalse() {
        val html = LatexRenderer.buildKatexHtml("x^2", displayMode = false, dark = true)
        assertTrue(html.contains("displayMode:false"))
    }

    @Test
    fun scriptBreakoutIsNeutralized() {
        val evil = "</script><script>alert(1)</script>"
        val html = LatexRenderer.buildKatexHtml(evil, displayMode = true, dark = false)
        // 模板只有兩個合法 script 標籤（katex.js 引入 + 渲染調用），注入的 </script> 必須被轉義。
        assertTrue(html.split("<script").size == 3)
        assertFalse(html.contains("</script><script>alert"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
    }

    @Test
    fun quotesAndBackslashesSurviveRoundTrip() {
        val raw = "a \"b\" \\ c"
        val escaped = LatexRenderer.escapeForJsString(raw)
        assertTrue(escaped.contains("\\\"b\\\""))
        assertTrue(escaped.contains("\\\\"))
    }

    @Test
    fun darkModeUsesLightText() {
        val dark = LatexRenderer.buildKatexHtml("x", displayMode = true, dark = true)
        val light = LatexRenderer.buildKatexHtml("x", displayMode = true, dark = false)
        assertTrue(dark.contains("#e8e8e8"))
        assertTrue(light.contains("#1a1a1a"))
    }
}
