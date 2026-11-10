package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.ui.components.EtaMonospace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGfmParserTest {
    @Test
    fun headingIsParsedAsHeadingFromFirstStreamingSnapshot() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "## 标题",
            isComplete = false,
        )

        assertEquals("## 标题", snapshot.renderedSource)
        val heading = snapshot.document.blocks.single() as MarkdownHeading
        assertEquals(2, heading.level)
        assertEquals("标题", heading.text.text)
    }

    @Test
    fun tableHeaderIsBufferedUntilDelimiterConfirmsTheBlock() {
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |",
                isComplete = false,
            ),
        )
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |\n| --",
                isComplete = false,
            ),
        )

        val confirmed = "| 指标 | 数值 |\n| --- | --- |"
        val snapshot = StreamingGfmParserSession().parse(
            source = confirmed,
            isComplete = false,
        )

        assertEquals(confirmed, snapshot.renderedSource)
        val table = snapshot.document.blocks.single() as MarkdownTable
        assertEquals(listOf("指标", "数值"), table.header.map { cell -> cell.text.text })
    }

    @Test
    fun ordinaryPipeTextIsReleasedWhenNextLineCannotBeATableDelimiter() {
        val source = "请选择 A | B\n这不是分隔行"

        assertEquals(
            source,
            StreamingGfmProjection.project(source = source, isComplete = false),
        )
    }

    @Test
    fun incompleteStrongDelimiterUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "说明 **压力",
            isComplete = false,
        )

        assertEquals("说明 **压力**", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("压力") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun incompleteInlineCodeUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "执行 `adb shell",
            isComplete = false,
        )

        assertEquals("执行 `adb shell`", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("adb shell") { it.fontFamily == EtaMonospace })
    }

    @Test
    fun incompleteLinkIsNotPublishedAsRawMarkdown() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "参考 [官方文档](https://example.com/do",
            isComplete = false,
        )

        assertEquals("参考 ", snapshot.renderedSource)
        assertFalse(snapshot.renderedSource.contains('['))
    }

    @Test
    fun openFenceRendersAsCodeWithoutChangingStoredSource() {
        val source = "```kotlin\nval answer = 42"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = false,
        )

        assertEquals(source, snapshot.originalSource)
        assertTrue(snapshot.renderedSource.endsWith("\n```"))
        val code = snapshot.document.blocks.single() as MarkdownCode
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.text.text)
    }

    @Test
    fun completionParsesExactOriginalSourceWithoutVirtualCharacters() {
        val source = "未闭合 **标记"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        assertEquals(source, snapshot.renderedSource)
        assertTrue(snapshot.isComplete)
    }

    @Test
    fun stableHandoffKeepsTheCompletedDocumentAndFormattedBlocks() {
        val parser = StreamingGfmParserSession()
        parser.parse("## 结果\n\n```kotlin\nval answer = 42", isComplete = false)
        val content = "## 结果\n\n```kotlin\nval answer = 42\n```\n\n**完成**"
        val snapshot = parser.parse(content, isComplete = true)
        val stableDocument = snapshot.completedDocumentFor(content)

        assertNotNull(stableDocument)
        assertSame(snapshot.document, stableDocument)
        val blocks = stableDocument!!.blocks
        assertTrue(blocks.any { it is MarkdownHeading && it.level == 2 })
        assertTrue(blocks.any { it is MarkdownCode && it.language == "kotlin" })
        val completion = blocks.filterIsInstance<MarkdownParagraph>().single()
        assertTrue(completion.text.hasSpanCovering("完成") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun unfinishedOrOutdatedSnapshotCannotBeUsedForStableHandoff() {
        val parser = StreamingGfmParserSession()
        val content = "回复内容"

        assertNull(parser.parse(content, isComplete = false).completedDocumentFor(content))
        val completed = parser.parse(content, isComplete = true)
        assertNull(completed.completedDocumentFor("回复内容和新补充"))
        assertNull(completed.completedDocumentFor("修正后的内容"))
    }

    @Test
    fun completedSnapshotIncludesReferenceLinksBeforeStableHandoff() {
        val content = "查看 [文档][eta]\n\n[eta]: https://example.com/docs"
        val snapshot = StreamingGfmParserSession().parse(content, isComplete = true)

        // 链接定义不产生块；完成快照里引用已解析为可点的 Url 注解。
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertEquals("查看 文档", paragraph.text.text)
        assertEquals(listOf("https://example.com/docs"), paragraph.text.linkUrls())
    }

    @Test
    fun correctedSnapshotDoesNotRetainOrMutatePreviousLinkDefinitions() {
        val parser = StreamingGfmParserSession()
        val original = parser.parse("[文档][eta]\n\n[eta]: https://example.com/docs", isComplete = true)
        val replacement = parser.parse("[文档][eta]", isComplete = true)

        // 没有对应定义的引用按原文保留，不携带链接注解。
        val replacementParagraph = replacement.document.blocks.single() as MarkdownParagraph
        assertEquals("[文档][eta]", replacementParagraph.text.text)
        assertTrue(replacementParagraph.text.linkUrls().isEmpty())

        val originalParagraph = original.document.blocks.single() as MarkdownParagraph
        assertEquals(listOf("https://example.com/docs"), originalParagraph.text.linkUrls())
    }

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

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, length).mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.url }
}
