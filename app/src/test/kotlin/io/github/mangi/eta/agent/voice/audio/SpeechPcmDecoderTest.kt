package io.github.mangi.eta.agent.voice.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.voice.tts.SpeechAudioStreamDecoder

class SpeechPcmDecoderTest {
    private val samples = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Test fun wavHeaderNeverReachesPlaybackForAnySplitPosition() {
        val wav = wav()
        for (split in 0..wav.size) {
            val output = ByteArrayOutputStream()
            val decoder = SpeechPcmDecoder(output::write)
            decoder.write(wav.copyOfRange(0, split))
            decoder.write(wav.copyOfRange(split, wav.size))
            decoder.end()
            assertArrayEquals("split=$split", samples, output.toByteArray())
        }
    }

    @Test fun unknownChunksAndOddPaddingAreSkippedBeforeData() {
        val wav = wav(metadata = true)
        val output = ByteArrayOutputStream()
        val decoder = SpeechPcmDecoder(output::write)
        for (byte in wav) decoder.write(byteArrayOf(byte))
        decoder.end()
        assertArrayEquals(samples, output.toByteArray())
    }

    @Test fun rawPcmPreservesSamplesAcrossOddSizedFragments() {
        val output = ByteArrayOutputStream()
        val decoder = SpeechPcmDecoder { bytes ->
            assertEquals(0, bytes.size % 2)
            output.write(bytes)
        }
        samples.forEach { decoder.write(byteArrayOf(it)) }
        decoder.end()
        assertArrayEquals(samples, output.toByteArray())
    }

    @Test fun shortRawPcmRemainsPlayable() {
        val output = ByteArrayOutputStream()
        val decoder = SpeechPcmDecoder(output::write)
        decoder.write(byteArrayOf(1, 2))
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }

    @Test fun truncatedHeadersEmptyAudioAndIncompleteSamplesFail() {
        for (size in 4..44) {
            val decoder = SpeechPcmDecoder { fail("Header must not be played") }
            decoder.write(wav().copyOf(size))
            assertThrows("size=$size", SpeechFailure::class.java) { decoder.end() }
        }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(1, 2, 3))) {
            val decoder = SpeechPcmDecoder {}
            decoder.write(bytes)
            assertThrows(SpeechFailure::class.java) { decoder.end() }
        }
    }

    @Test fun incompatibleWavFormatsFailBeforePlayingAnySamples() {
        for ((offset, value) in listOf(20 to 3, 22 to 2, 24 to 16000, 28 to 32000, 32 to 4, 34 to 8)) {
            val invalid = wav()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
            val decoder = SpeechPcmDecoder { fail("Invalid format must not be played") }
            assertThrows(SpeechFailure::class.java) { decoder.write(invalid) }
        }
    }

    @Test fun oversizedMetadataFailsWithoutWaitingForItsBody() {
        val prefix = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(Int.MAX_VALUE).put("WAVE".toByteArray())
            .put("JUNK".toByteArray()).putInt(Int.MAX_VALUE).array()
        val decoder = SpeechPcmDecoder { fail("Metadata must not be played") }
        assertThrows(SpeechFailure::class.java) { decoder.write(prefix) }
    }

    @Test fun qwenSseNormalizesEachSynthesisIndependently() {
        val output = ByteArrayOutputStream()
        repeat(2) {
            val decoder = SpeechAudioStreamDecoder(true, output::write)
            val wav = wav()
            for (bytes in wav.toList().chunked(3)) {
                val event = JSONObject().put("output", JSONObject().put("audio", JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(bytes.toByteArray()))))
                decoder.line("data: $event")
                decoder.line("")
            }
            decoder.line("data: {\"output\":{\"finish_reason\":\"stop\"}}")
            decoder.line("")
            decoder.end()
        }
        assertArrayEquals(samples + samples, output.toByteArray())
    }

    private fun wav(metadata: Boolean = false): ByteArray {
        val size = 44 + samples.size + if (metadata) 12 else 0
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(0x7fffffbf).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24000).putInt(48000).putShort(2).putShort(16)
            .apply { if (metadata) put("JUNK".toByteArray()).putInt(3).put(byteArrayOf(7, 8, 9, 0)) }
            .put("data".toByteArray()).putInt(0x7fffff9b).put(samples).array()
    }
}
