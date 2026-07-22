package fuck.andes.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothTextRevealPolicyTest {
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
    fun commonPrefixNeverEndsInsideAChangedSurrogatePair() {
        assertEquals(0, commonUtf16PrefixLength("😀 alpha", "😁 beta"))
        assertEquals(3, commonUtf16PrefixLength("A😀x", "A😀y"))
        assertEquals(0, commonUtf16PrefixLength("first", "second"))
    }

    @Test
    fun changedExtendedGraphemeSnapsPreservedPrefixToPreviousBoundary() {
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"
        val commonPrefixEnd = commonUtf16PrefixLength(previous, replacement)
        val preservedBoundary = graphemeBoundaries(replacement)
            .last { boundary -> boundary <= commonPrefixEnd }

        assertEquals(3, commonPrefixEnd)
        assertEquals(0, preservedBoundary)
    }

    @Test
    fun revealSpeedUsesBaseRateThenCatchesUpAndCaps() {
        assertEquals(48f, smoothRevealSpeed(totalBacklog = 0f), FLOAT_TOLERANCE)
        assertEquals(48f, smoothRevealSpeed(totalBacklog = 9f), FLOAT_TOLERANCE)
        assertEquals(100f, smoothRevealSpeed(totalBacklog = 20f), FLOAT_TOLERANCE)
        assertEquals(240f, smoothRevealSpeed(totalBacklog = 1_000f), FLOAT_TOLERANCE)
    }

    @Test
    fun normalFrameAdvancesFractionallyAtBaseRate() {
        assertEquals(
            0.8f,
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
    fun catchUpStillAdvancesAtMostOneGraphemePerFrame() {
        assertEquals(
            4f,
            advanceSmoothReveal(
                current = 3f,
                target = 100f,
                elapsedSeconds = 1f,
                totalBacklog = 1_000f,
            ),
            FLOAT_TOLERANCE,
        )
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
    fun markdownBatchEndsOnlyAfterCompleteGraphemes() {
        val content = "A👨‍👩‍👧‍👦中B"

        assertEquals(12, streamingMarkdownBatchEnd(content, start = 0, maxGraphemes = 2))
        assertEquals(13, streamingMarkdownBatchEnd(content, start = 12, maxGraphemes = 1))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 13, maxGraphemes = 8))
    }

    @Test
    fun markdownBatchCompletesGraphemeExtendedAcrossPreviousChunk() {
        val content = "A\u0301B"

        assertEquals(2, streamingMarkdownBatchEnd(content, start = 1, maxGraphemes = 1))
        assertEquals(0, streamingMarkdownBatchEnd(content, start = -2, maxGraphemes = 0))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 99, maxGraphemes = 4))
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
