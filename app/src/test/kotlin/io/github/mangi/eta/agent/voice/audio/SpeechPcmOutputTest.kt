package io.github.mangi.eta.agent.voice.audio

import android.app.Application
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [SpeechPcmOutputTest.BufferedAudioTrack::class])
class SpeechPcmOutputTest {
    @Before fun reset() {
        BufferedAudioTrack.starts.clear()
        BufferedAudioTrack.thresholds.clear()
        BufferedAudioTrack.writtenBytes = 0
        BufferedAudioTrack.capacityBytes = 24_000
    }

    @Test fun waitsForBufferAcrossChunksBeforeStarting() {
        SpeechPcmOutput().use { output ->
            assertTrue(BufferedAudioTrack.starts.isEmpty())
            output.write(ByteArray(8_000))
            output.write(ByteArray(8_000))
            assertTrue(BufferedAudioTrack.starts.isEmpty())
            assertFalse(output.drained())
            output.write(ByteArray(8_000))
            assertEquals(listOf(24_000), BufferedAudioTrack.starts)
            output.write(ByteArray(4_000))
            output.finish()
            output.finish()
            assertEquals(listOf(24_000), BufferedAudioTrack.starts)
            assertTrue(BufferedAudioTrack.thresholds.isEmpty())
            assertTrue(output.drained())
        }
    }

    @Test fun oversizedChunkStartsAtCapacityAndWritesTheRemainder() {
        SpeechPcmOutput().use { output ->
            output.write(ByteArray(48_000))
            assertEquals(listOf(24_000), BufferedAudioTrack.starts)
            assertEquals(48_000, BufferedAudioTrack.writtenBytes)
            output.finish()
            assertTrue(output.drained())
        }
    }

    @Test fun shortAudioStartsOnlyWhenInputFinishes() {
        SpeechPcmOutput().use { output ->
            output.write(byteArrayOf(1, 2, 3, 4))
            assertTrue(BufferedAudioTrack.starts.isEmpty())
            assertFalse(output.drained())
            output.finish()
            output.finish()
            assertEquals(listOf(2), BufferedAudioTrack.thresholds)
            assertEquals(listOf(4), BufferedAudioTrack.starts)
            assertTrue(output.drained())
            assertThrows(IllegalStateException::class.java) { output.write(byteArrayOf(5, 6)) }
        }
    }

    @Test fun shortWriteStartsBeforeRetryingTheUnwrittenBytes() {
        BufferedAudioTrack.capacityBytes = 16_000
        SpeechPcmOutput().use { output ->
            output.write(ByteArray(24_000))
            assertEquals(listOf(16_000), BufferedAudioTrack.starts)
            assertEquals(24_000, BufferedAudioTrack.writtenBytes)
            output.finish()
            assertTrue(output.drained())
        }
    }

    @Test fun emptyInputFinishesWithoutStartingAudio() {
        SpeechPcmOutput().use { output ->
            output.finish()
            assertTrue(BufferedAudioTrack.starts.isEmpty())
            assertTrue(BufferedAudioTrack.thresholds.isEmpty())
            assertTrue(output.drained())
        }
    }

    @Test fun cancellationDuringPrimingDoesNotStartAudio() {
        val output = SpeechPcmOutput()
        output.write(byteArrayOf(1, 2))
        output.close()
        output.close()
        assertTrue(BufferedAudioTrack.starts.isEmpty())
        assertTrue(output.drained())
        assertThrows(CancellationException::class.java) { output.write(byteArrayOf(3, 4)) }
        assertThrows(CancellationException::class.java) { output.finish() }
    }

    /** 默认 Shadow 无容量限制；这里模拟停止状态下写满缓冲区的短写，验证启动前不会死锁。 */
    @Implements(AudioTrack::class)
    class BufferedAudioTrack : ShadowAudioTrack() {
        private var started = false
        private var acceptedBytes = 0

        @Implementation override fun native_write_byte(
            data: ByteArray, offset: Int, size: Int, format: Int, blocking: Boolean,
        ): Int {
            val count = if (started) size else minOf(size, capacityBytes - acceptedBytes)
            val written = super.native_write_byte(data, offset, count, format, blocking)
            acceptedBytes += written
            writtenBytes += written
            return written
        }

        @Implementation override fun play() {
            starts.add(acceptedBytes)
            started = true
            super.play()
        }

        @Implementation fun setStartThresholdInFrames(frames: Int): Int {
            thresholds.add(frames)
            return frames
        }

        companion object {
            val starts = mutableListOf<Int>()
            val thresholds = mutableListOf<Int>()
            var writtenBytes = 0
            var capacityBytes = 24_000
        }
    }
}
