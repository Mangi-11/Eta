package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.GeminiTtsConfig
import io.github.mangi.eta.data.model.MiniMaxTtsConfig
import java.io.ByteArrayOutputStream
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudSpeechSynthesisTest {
    @Test fun geminiKeepsStyleSeparateFromTranscriptAndDisablesStorage() {
        val request = geminiSpeechRequest(
            GeminiTtsConfig(baseUrl = "https://example.invalid/proxy/", style = "温和自然"), "test-key", "原文",
        )
        assertEquals("/proxy/v1beta/interactions", request.url.encodedPath)
        assertEquals("test-key", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        assertNull(request.url.query)
        val body = body(request)
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("gemini-3.8-flash-tts", body.getString("model"))
        val text = body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("原文", text.getString("text"))
        assertEquals("温和自然", text.getJSONArray("annotations").getJSONObject(0).getString("style"))
        assertEquals("audio/l16", body.getJSONObject("response_format").getString("mime_type"))
        assertEquals(24000, body.getJSONObject("response_format").getInt("sample_rate"))
        assertEquals("Kore", body.getJSONObject("generation_config").getJSONArray("speech_config")
            .getJSONObject(0).getString("voice"))
        val plain = body(geminiSpeechRequest(GeminiTtsConfig(), "test-key", "原文"))
        assertFalse(plain.getJSONArray("input").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).has("annotations"))
    }

    @Test fun minimaxRequestsPcmWithNormalizationAndNoAggregate() {
        val request = minimaxSpeechRequest(MiniMaxTtsConfig(speed = 1.25f), "test-key", "下午 3 点")
        assertEquals("/v1/t2a_v2", request.url.encodedPath)
        assertEquals("Bearer test-key", request.header("Authorization"))
        val body = body(request)
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("exclude_aggregated_audio"))
        assertEquals("basic", body.getString("text_normalization_mode"))
        assertEquals(1.25, body.getJSONObject("voice_setting").getDouble("speed"), 0.0)
        assertEquals("pcm", body.getJSONObject("audio_setting").getString("format"))
        assertEquals(24000, body.getJSONObject("audio_setting").getInt("sample_rate"))
        assertEquals(1, body.getJSONObject("audio_setting").getInt("channel"))
    }

    @Test fun geminiDecodesFragmentedSseAndStopsAtSuccessfulCompletion() {
        val out = ByteArrayOutputStream()
        val decoder = GeminiSpeechDecoder(out::write)
        val wire = Buffer().writeUtf8(
            ": keepalive\r\n\r\n" +
                "event: step.delta\r\ndata: {\"delta\":\r\ndata: {\"type\":\"audio\",\"data\":\"AQ==\"}}\r\n\r\n" +
                "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AgME\"}}\n\n" +
                "event: interaction.completed\ndata: {\"interaction\":{\"status\":\"completed\"}}\n\n" +
                "data: invalid trailing event\n\n",
        )
        val fragmented = object : ForwardingSource(wire) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(byteCount, 1))
        }.buffer()
        readSpeechSse(fragmented, decoder::event)
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out.toByteArray())
    }

    @Test fun geminiRequiresAudioAndSuccessRatherThanDoneOrEof() {
        for (terminal in listOf("", "data: [DONE]\n\n", geminiComplete)) {
            val decoder = GeminiSpeechDecoder {}
            readSpeechSse(Buffer().writeUtf8(terminal), decoder::event)
            assertThrows(SpeechFailure::class.java) { decoder.end() }
        }
        val partial = GeminiSpeechDecoder {}
        partial.event("step.delta", """{"delta":{"type":"audio","data":"AQI="}}""")
        assertThrows(SpeechFailure::class.java) { partial.end() }
        for (status in listOf("cancelled", "incomplete", "failed", "requires_action")) {
            assertThrows(SpeechFailure::class.java) {
                partial.event("interaction.completed", """{"interaction":{"status":"$status"}}""")
            }
        }
    }

    @Test fun geminiRejectsIncompatibleAudioAndRedactsErrors() {
        for (metadata in listOf("\"mime_type\":\"audio/mp3\"", "\"sample_rate\":16000", "\"channels\":2")) {
            val decoder = GeminiSpeechDecoder {}
            assertThrows(SpeechFailure::class.java) {
                decoder.event("step.delta", """{"delta":{"type":"audio","data":"AQI=",$metadata}}""")
            }
        }
        val error = assertThrows(SpeechFailure::class.java) {
            GeminiSpeechDecoder {}.event("error",
                """{"error":{"status":"RESOURCE_EXHAUSTED","message":"private response"}}""")
        }
        assertEquals(SpeechErrorCode.RATE_LIMITED, error.code)
        assertFalse(error.userMessage.contains("private"))
        assertFalse(error.toString().contains("private"))
    }

    @Test fun minimaxNeverReplaysFinalAggregateAndAcceptsUppercaseHex() {
        val out = ByteArrayOutputStream()
        val decoder = MiniMaxSpeechDecoder(out::write)
        readSpeechSse(Buffer().writeUtf8(
            "data: ${miniChunk("01", 1)}\n\n" +
                "data: ${miniChunk("0203FF", 1)}\n\n" +
                "data: ${miniChunk("010203FF", 2)}",
        )) { _, data -> decoder.event(data) }
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2, 3, -1), out.toByteArray())
    }

    @Test fun minimaxRejectsMissingTerminalMalformedHexAndEmptyAudio() {
        for (hex in listOf("0", "XY")) {
            assertThrows(SpeechFailure::class.java) { MiniMaxSpeechDecoder {}.event(miniChunk(hex, 1)) }
        }
        val partial = MiniMaxSpeechDecoder {}
        partial.event(miniChunk("0102", 1))
        assertThrows(SpeechFailure::class.java) { partial.end() }
        val empty = MiniMaxSpeechDecoder {}
        empty.event(miniChunk("0102", 2))
        assertThrows(SpeechFailure::class.java) { empty.end() }
        assertThrows(SpeechFailure::class.java) { MiniMaxSpeechDecoder {}.event(miniChunk("0102", 9)) }
    }

    @Test fun minimaxBusinessErrorsAreClassifiedBeforeReadingNullData() {
        for ((code, expected) in listOf(
            1004 to SpeechErrorCode.AUTHENTICATION,
            1039 to SpeechErrorCode.RATE_LIMITED,
            1001 to SpeechErrorCode.TIMEOUT,
            2013 to SpeechErrorCode.CONFIGURATION,
        )) {
            val error = assertThrows(SpeechFailure::class.java) {
                MiniMaxSpeechDecoder {}.event(
                    """{"base_resp":{"status_code":$code,"status_msg":"private response"},"data":null}""",
                )
            }
            assertEquals(expected, error.code)
            assertFalse(error.userMessage.contains("private"))
        }
    }

    @Test fun sseAndAudioHaveIndependentSizeLimits() {
        val frame = SpeechSseDecoder { _, _ -> fail("Oversized event must not be dispatched") }
        frame.line("data: " + "x".repeat(1024 * 1024))
        assertThrows(SpeechFailure::class.java) { frame.line("data: " + "x".repeat(1024 * 1024)) }
        assertThrows(SpeechFailure::class.java) {
            readSpeechSse(Buffer().writeUtf8("x".repeat(2 * 1024 * 1024 + 1))) { _, _ -> true }
        }
        val audio = SpeechSegmentAudio({}, limit = 4)
        audio.write(byteArrayOf(1, 2, 3, 4))
        assertThrows(SpeechFailure::class.java) { audio.write(byteArrayOf(5, 6)) }
    }

    private fun body(request: Request): JSONObject = JSONObject(
        Buffer().also { request.body!!.writeTo(it) }.readUtf8(),
    )

    private fun miniChunk(hex: String, status: Int) =
        """{"base_resp":{"status_code":0},"data":{"audio":"$hex","status":$status}}"""

    private val geminiComplete = "data: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}\n\n"
}
