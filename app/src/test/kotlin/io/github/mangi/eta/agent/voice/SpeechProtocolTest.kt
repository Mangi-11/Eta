package io.github.mangi.eta.agent.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeechProtocolTest {
    @Test fun qwenRevisionsReplaceDraftAndLatePreviewCannotReplaceFinal() {
        val transcript = QwenTranscript()
        fun event(id: String, suffix: String, text: String, stash: String = "") = JSONObject()
            .put("type", "conversation.item.input_audio_transcription.$suffix")
            .put("item_id", id).put("text", text).put("stash", stash).put("transcript", text)
        assertEquals("北京的", transcript.accept(event("a", "text", "", "北京的")))
        assertEquals("北京天气", transcript.accept(event("a", "text", "北京", "天气")))
        assertEquals("北京天气。", transcript.accept(event("a", "completed", "北京天气。")))
        assertEquals("北京天气。", transcript.accept(event("a", "text", "旧草稿")))
        transcript.accept(event("b", "completed", "请查一下。"))
        transcript.accept(event("c", "text", "未确认的内容"))
        assertEquals("北京天气。请查一下。", transcript.result())
    }

    @Test fun doubaoFramesHandleCompressionHeaderExtensionsAndFinalSequence() {
        val json = """{"result":{"text":"你好"}}""".toByteArray()
        for (gzip in listOf(false, true)) {
            val payload = if (gzip) ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(json) } }.toByteArray() else json
            val frame = ByteBuffer.allocate(16 + payload.size)
                .put(0x12).put(0x93.toByte()).put(if (gzip) 0x11 else 0x10).put(0)
                .putInt(0).putInt(-2).putInt(payload.size).put(payload).array()
            val result = DoubaoAsrCodec.decode(frame)
            assertTrue(result.final)
            assertEquals("你好", result.body.getJSONObject("result").getString("text"))
            assertThrows(SpeechFailure::class.java) { DoubaoAsrCodec.decode(frame.copyOf(frame.size - 1)) }
        }
    }

    @Test fun doubaoServerErrorDoesNotExposeServerBody() {
        val frame = ByteBuffer.allocate(12).put(0x11).put(0xf0.toByte()).put(0x10).put(0)
            .putInt(45000001).putInt(0).array()
        val error = assertThrows(SpeechFailure::class.java) { DoubaoAsrCodec.decode(frame) }
        assertEquals(SpeechErrorCode.SERVER, error.code)
        assertTrue(error.userMessage.contains("45000001"))
    }

    @Test fun qwenSseUsesEventBoundariesAndDoesNotReplayFinalUrl() {
        val chunks = mutableListOf<ByteArray>()
        val decoder = SpeechAudioStreamDecoder(true, chunks::add)
        decoder.line(": ping")
        decoder.line("data: {\"output\":")
        decoder.line("data: {\"audio\":{\"data\":\"AQI=\"}}}")
        assertTrue(chunks.isEmpty())
        decoder.line("")
        decoder.line("data: {\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"data\":\"\",\"url\":\"https://example.invalid/audio\"}}}")
        decoder.end()
        assertTrue(decoder.finished)
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2), chunks.single())
    }

    @Test fun synthesisRequiresTerminalSuccessAndNonEmptyAudio() {
        for (qwen in listOf(true, false)) {
            val decoder = SpeechAudioStreamDecoder(qwen) {}
            decoder.line(if (qwen) "data: {\"output\":{\"audio\":{\"data\":\"AQI=\"}}}" else "{\"code\":0,\"data\":\"AQI=\"}")
            assertThrows(SpeechFailure::class.java) { decoder.end() }
        }
        val empty = SpeechAudioStreamDecoder(false) {}
        empty.line("{\"code\":20000000}")
        assertThrows(SpeechFailure::class.java) { empty.end() }
        val failed = SpeechAudioStreamDecoder(false) {}
        assertThrows(SpeechFailure::class.java) { failed.line("{\"code\":45000001,\"message\":\"private content\"}") }
    }

    @Test fun doubaoAudioChunksPreserveOrder() {
        val out = ByteArrayOutputStream()
        val decoder = SpeechAudioStreamDecoder(false, out::write)
        decoder.line("{\"code\":0,\"data\":\"AQI=\"}")
        decoder.line("{\"code\":0,\"data\":\"AwQ=\"}")
        decoder.line("{\"code\":20000000}")
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out.toByteArray())
    }

    @Test fun fileTranscriptReadsChannelText() {
        assertEquals("完整转写", fileTranscript(JSONObject("""{"transcripts":[{"channel_id":0,"text":"完整转写","sentences":[]}]}""")))
    }

    @Test fun wavHeaderDescribesRecordedPcm() {
        val wav = pcmToWav(byteArrayOf(0, 1, 2, 3))
        val header = ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", wav.take(4).toByteArray().toString(Charsets.US_ASCII))
        assertEquals(40, header.getInt(4))
        assertEquals(16000, header.getInt(24))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), wav.copyOfRange(44, 48))
    }

    @Test fun recordingCollectionRejectsEmptyAudio() {
        val frames = Channel<ByteArray>(1).apply { close() }
        val error = assertThrows(SpeechFailure::class.java) {
            runBlocking { collectSpeechAudio(frames) }
        }
        assertEquals(SpeechErrorCode.NO_SPEECH, error.code)
    }

    @Test fun recordingCollectionAcceptsItsLimitButRejectsAdditionalFrames() {
        val pcm = ByteArray(SpeechRecorder.MAX_BYTES)
        val complete = Channel<ByteArray>(1).apply { trySend(pcm); close() }
        val wav = runBlocking { collectSpeechAudio(complete) }
        assertEquals(pcm.size + 44, wav.size)

        val oversized = Channel<ByteArray>(2).apply {
            trySend(pcm)
            trySend(byteArrayOf(0, 0))
            close()
        }
        val error = assertThrows(SpeechFailure::class.java) {
            runBlocking { collectSpeechAudio(oversized) }
        }
        assertEquals(SpeechErrorCode.AUDIO, error.code)
    }
}
