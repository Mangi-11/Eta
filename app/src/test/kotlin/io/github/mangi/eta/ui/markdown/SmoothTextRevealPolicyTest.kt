package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTextRevealPolicyTest {
    @Test
    fun coordinatorTracksBackgroundAnimationSuspension() {
        val coordinator = SmoothTextRevealCoordinator()

        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        assertEquals(true, coordinator.isAnimationPaused)
        coordinator.resumeAnimationsAfterCatchUp()
        assertEquals(false, coordinator.isAnimationPaused)
    }

    @Test
    fun emptyAndOrdinaryTextExposeEveryGraphemeBoundary() {
        assertArrayEquals(intArrayOf(0), graphemeBoundaries(""))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), graphemeBoundaries("A中B"))
    }

    @Test
    fun emojiSurrogatePairIsOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A😀B"),
        )
    }

    @Test
    fun extendedEmojiSequencesAreNeverSplit() {
        assertArrayEquals(
            intArrayOf(0, 1, 12, 13),
            graphemeBoundaries("A👨‍👩‍👧‍👦B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A👍🏽B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A🇨🇳B"),
        )
    }

    @Test
    fun combiningMarkAndCrLfStayInOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("Ae\u0301B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A\r\nB"),
        )
    }

    @Test
    fun appendedTextOnlyRebuildsTheLastPotentiallyExtendedGrapheme() {
        val familyPrefix = "A👨‍👩"
        val prefixBoundaries = graphemeBoundaries(familyPrefix)
        val family = "$familyPrefix‍👧‍👦B"

        assertArrayEquals(
            graphemeBoundaries(family),
            updateGraphemeBoundaries(familyPrefix, prefixBoundaries, family),
        )
        assertArrayEquals(
            graphemeBoundaries("A\r\nB"),
            updateGraphemeBoundaries("A\r", graphemeBoundaries("A\r"), "A\r\nB"),
        )
    }

    @Test
    fun nonAppendReplacementFallsBackToACompleteBoundaryScan() {
        val previous = "alpha 😀"
        val replacement = "beta 👨‍👩‍👧‍👦"

        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun replacementSharingPrefixInsideSurrogatePairFallsBackToCompleteScan() {
        // 增量重建只处理纯追加；替换即使共享 UTF-16 前缀也整体重扫，不会切开代理对。
        assertReplacementFallsBackToCompleteScan(previous = "😀 alpha", replacement = "😁 beta")
        assertReplacementFallsBackToCompleteScan(previous = "A😀x", replacement = "A😀y")
        assertReplacementFallsBackToCompleteScan(previous = "first", replacement = "second")
    }

    @Test
    fun changedExtendedGraphemeFallsBackToCompleteScanWithoutSplitting() {
        // “👨‍👩X”与“👨‍👧Y”共享的前缀结束在 ZWJ 序列中间，只能整体重扫而不是复用半截字素。
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"

        assertArrayEquals(
            intArrayOf(0, 5, 6),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun revealSpeedUsesBaseRateThenCatchesUpWithoutCeiling() {
        assertEquals(36f, smoothRevealSpeed(totalBacklog = 0f), FLOAT_TOLERANCE)
        assertEquals(45f, smoothRevealSpeed(totalBacklog = 9f), FLOAT_TOLERANCE)
        assertEquals(100f, smoothRevealSpeed(totalBacklog = 20f), FLOAT_TOLERANCE)
        assertEquals(5_000f, smoothRevealSpeed(totalBacklog = 1_000f), FLOAT_TOLERANCE)
        assertEquals(50_000f, smoothRevealSpeed(totalBacklog = 10_000f), FLOAT_TOLERANCE)
    }

    @Test
    fun normalFrameAdvancesFractionallyAtBaseRate() {
        assertEquals(
            0.6f,
            advanceSmoothReveal(
                current = 0f,
                target = 10f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = 1f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun catchUpAdvancesBeyondFormerSpeedCap() {
        assertEquals(
            253f,
            advanceSmoothReveal(
                current = 3f,
                target = 1_000f,
                elapsedSeconds = 0.05f,
                totalBacklog = 1_000f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun sustainedFastOutputDoesNotAccumulateUnboundedBacklog() {
        var target = 0f
        var progress = 0f
        repeat(600) {
            target += 20f
            progress = advanceSmoothReveal(progress, target, 1f / 60f, target - progress)
        }
        assertTrue(target - progress < 300f)
        repeat(90) {
            progress = advanceSmoothReveal(progress, target, 1f / 60f, target - progress)
        }
        assertEquals(target, progress, FLOAT_TOLERANCE)
    }

    @Test
    fun frameAdvanceClampsInvalidTimeAndTargetBounds() {
        assertEquals(
            2f,
            advanceSmoothReveal(
                current = 2f,
                target = 10f,
                elapsedSeconds = -1f,
                totalBacklog = 10f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 4.75f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 7f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun markdownDocumentCollapsesSourceBlankLinesIntoSemanticBlocks() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "第一段\n\n\n第二段",
            isComplete = true,
        )

        val blocks = snapshot.document.blocks
        assertEquals(2, blocks.size)
        assertEquals(listOf("第一段", "第二段"), blocks.map { block -> (block as MarkdownParagraph).text.text })
    }

    @Test
    fun markdownBlockGapBuildsReadableHierarchyWithoutLeadingGap() {
        val first = MarkdownParagraph(0, AnnotatedString("第一段"))
        val second = MarkdownParagraph(4, AnnotatedString("第二段"))
        val heading = MarkdownHeading(8, level = 2, text = AnnotatedString("小节"))
        val subHeading = MarkdownHeading(12, level = 3, text = AnnotatedString("子节"))
        val list = MarkdownList(16, ordered = false, loose = false, items = emptyList())
        val table = MarkdownTable(20, alignments = emptyList(), header = emptyList(), rows = emptyList())
        val rule = MarkdownRule(24)
        val quote = MarkdownQuote(28, blocks = emptyList())

        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Answer))
        assertEquals(11.sp, markdownBlockGap(first, second, MarkdownTone.Answer))
        assertEquals(20.sp, markdownBlockGap(first, heading, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(first, subHeading, MarkdownTone.Answer))
        assertEquals(6.sp, markdownBlockGap(heading, second, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(heading, subHeading, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(first, list, MarkdownTone.Answer))
        assertEquals(13.sp, markdownBlockGap(table, first, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(first, rule, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(rule, heading, MarkdownTone.Answer))
        assertEquals(5.sp, markdownCompactGap(first, list, MarkdownTone.Answer))
        assertEquals(8.sp, markdownCompactGap(first, quote, MarkdownTone.Answer))

        // 思考语气的间距整体收敛。
        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Thinking))
        assertEquals(8.25.sp, markdownBlockGap(first, second, MarkdownTone.Thinking))
        assertEquals(15.sp, markdownBlockGap(first, heading, MarkdownTone.Thinking))
    }

    @Test
    fun listMarkerWaitsForItsOwnContentToStartRevealing() {
        val markerKey = RevealBlockKey(10)

        assertFalse(listMarkerVisible(revealActive = true, markerKey = markerKey, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = true, markerKey = markerKey, started = setOf(markerKey)))
    }

    @Test
    fun listMarkerStaysHiddenForEmptyItemsUntilRevealEnds() {
        // 图片已降级为行内链接，不再存在“项内无文字但因图片而恒可见”的特例；
        // 空项在流式期间保持隐藏，非流式渲染不再隐藏任何 marker。
        assertFalse(listMarkerVisible(revealActive = true, markerKey = null, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = null, started = null))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = RevealBlockKey(10), started = emptySet()))
    }

    private fun assertReplacementFallsBackToCompleteScan(previous: String, replacement: String) {
        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
