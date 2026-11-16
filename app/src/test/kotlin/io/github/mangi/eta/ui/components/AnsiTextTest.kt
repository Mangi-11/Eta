package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTextTest {

    @Test
    fun plainTextPassesThroughWithoutSpans() {
        val parsed = ansiToAnnotatedString("hello world")
        assertEquals("hello world", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun sgrColorProducesSpanAndStrippedText() {
        val parsed = ansiToAnnotatedString("\u001B[31mred\u001B[0m plain")
        assertEquals("red plain", parsed.text)
        val span = parsed.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(3, span.end)
        assertEquals(Color(0xFFCD0000), span.item.color)
    }

    @Test
    fun truecolorAnd256ColorAreResolved() {
        val truecolor = ansiToAnnotatedString("\u001B[38;2;10;20;30mx")
        assertEquals(Color(0xFF0A141E), truecolor.spanStyles.single().item.color)

        val cube = ansiToAnnotatedString("\u001B[38;5;196mx")
        assertEquals(Color(0xFFFF0000), cube.spanStyles.single().item.color)

        val gray = ansiToAnnotatedString("\u001B[38;5;255mx")
        assertEquals(Color(0xFFEEEEEE), gray.spanStyles.single().item.color)
    }

    @Test
    fun brightAndBackgroundColorsAreResolved() {
        val bright = ansiToAnnotatedString("\u001B[91mx")
        assertEquals(Color(0xFFFF0000), bright.spanStyles.single().item.color)
        val bg = ansiToAnnotatedString("\u001B[42mx")
        assertEquals(Color(0xFF00CD00), bg.spanStyles.single().item.background)
    }

    @Test
    fun boldUnderlineAndResetAreApplied() {
        val parsed = ansiToAnnotatedString("\u001B[1;4mboth\u001B[0mnormal")
        assertEquals("bothnormal", parsed.text)
        val span = parsed.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }

    @Test
    fun carriageReturnOverwritesCurrentLine() {
        val parsed = ansiToAnnotatedString("progress 10%\rprogress 90%\ndone")
        assertEquals("progress 90%\ndone", parsed.text)
    }

    @Test
    fun carriageReturnKeepsEarlierLinesAndStyleState() {
        val parsed = ansiToAnnotatedString("\u001B[31mfirst\nabc\rdef")
        assertEquals("first\ndef", parsed.text)
        // \r 之前的颜色状态在行覆盖后仍然保留（与真实终端一致）。
        val lastLine = parsed.spanStyles.last()
        assertEquals(Color(0xFFCD0000), lastLine.item.color)
    }

    @Test
    fun oscAndCursorSequencesAreDropped() {
        val parsed = ansiToAnnotatedString("\u001B[2K\u001B[1Ghi\u001B]8;;http://example.com\u0007link\u001B]8;;\u0007!")
        assertEquals("hilink!", parsed.text)
    }

    @Test
    fun incompleteTrailingSequenceIsDropped() {
        val parsed = ansiToAnnotatedString("text\u001B[38;2")
        assertEquals("text", parsed.text)
    }

    @Test
    fun plainTextStripsAllSequences() {
        assertEquals("abc", ansiPlainText("\u001B[1;32ma\u001B[0mb\u001B]9;title\u0007c"))
    }

    @Test
    fun crlfLineEndingsDoNotEraseContent() {
        val parsed = ansiToAnnotatedString("first line\r\nsecond line\r\n")
        assertEquals("first line\nsecond line\n", parsed.text)
    }

    @Test
    fun realColorCliBannerKeepsTextAndColors() {
        // 真实 CLI 横幅捕获（含 CRLF、256 色与粗体 SGR）。
        val raw = "\r\n  \u001B[38;5;111m▐█▛█▛█▌\u001B[39m  \u001B[1m\u001B[38;5;111mLocal server ready\u001B[39m\u001B[22m  \u001B[38;5;244m0.39.1\u001B[39m\r\n  \u001B[38;5;111m▐█████▌\u001B[39m  \u001B[38;5;244mLocal web UI is available from this machine.\u001B[39m\r\n"
        val parsed = ansiToAnnotatedString(raw)
        assertTrue(parsed.text.contains("Local server ready"))
        assertTrue(parsed.text.contains("▐█▛█▛█▌"))
        // 38;5;111 → cube(135,175,255)
        assertTrue(parsed.spanStyles.any { it.item.color == Color(0xFF87AFFF) })
    }
}
