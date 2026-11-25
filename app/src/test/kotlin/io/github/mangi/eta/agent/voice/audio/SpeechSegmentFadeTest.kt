package io.github.mangi.eta.agent.voice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmentFadeTest {
    private fun pcm(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it.toShort()) } }.array()

    private fun samples(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 2) { buffer.short.toInt() }
    }

    private fun segment(fade: SpeechSegmentFade, vararg parts: ByteArray): List<Int> =
        samples(parts.fold(ByteArray(0)) { output, part -> output + fade.write(part) } + fade.end())

    @Test
    fun segmentEdgesStartAndEndAtSilenceWhileTheMiddleIsUntouched() {
        val output = segment(SpeechSegmentFade(fadeSamples = 4), pcm(*IntArray(12) { 10_000 }))

        assertEquals(listOf(0, 2_500, 5_000, 7_500, 10_000, 10_000, 10_000, 10_000, 7_500, 5_000, 2_500, 0), output)
    }

    @Test
    fun splittingTheStreamDoesNotChangeTheResult() {
        val source = pcm(*IntArray(40) { (it - 20) * 1_000 })
        val whole = segment(SpeechSegmentFade(fadeSamples = 6), source)
        for (split in 0..source.size step 2) {
            val parts = segment(SpeechSegmentFade(fadeSamples = 6),
                source.copyOfRange(0, split), source.copyOfRange(split, source.size))
            assertEquals("split=$split", whole, parts)
        }
    }

    @Test
    fun consecutiveSegmentsMeetAtSilence() {
        val fade = SpeechSegmentFade(fadeSamples = 4)
        val first = segment(fade, pcm(*IntArray(10) { -20_000 }))
        val second = segment(fade, pcm(*IntArray(10) { 20_000 }))

        assertEquals(0, first.last())
        assertEquals(0, second.first())
        assertTrue(second.contains(20_000))
    }

    @Test
    fun segmentsShorterThanTheFadeStayBoundedAndComplete() {
        val output = segment(SpeechSegmentFade(fadeSamples = 120), pcm(32_767, -32_768, 32_767))

        assertEquals(3, output.size)
        assertTrue(output.all { it in -32_768..32_767 })
        assertArrayEquals(ByteArray(0), SpeechSegmentFade().end())
    }
}
