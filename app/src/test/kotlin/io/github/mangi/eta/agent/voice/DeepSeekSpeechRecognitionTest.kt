package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.DeepSeekAsrConfig
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSpeechRecognitionTest {
    @Test fun requestUsesMultipartFormAndBearerAuth() {
        val wav = pcmToWav(byteArrayOf(0, 1, 2, 3))
        val request = deepSeekAsrRequest(DeepSeekAsrConfig(), " test-key ", wav)
        assertEquals("https://api.deepseek.com/v1/tools/asr", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
        val rawBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(rawBody.contains("name=\"file\""))
        assertTrue(rawBody.contains("filename=\"speech.wav\""))
    }

    @Test fun requestPreservesGatewayPrefixAndChecksSizeLimit() {
        val request = deepSeekAsrRequest(
            DeepSeekAsrConfig(baseUrl = " https://example.invalid/proxy/ "),
            "test-key",
            pcmToWav(byteArrayOf(0, 0)),
        )
        assertEquals("/proxy/v1/tools/asr", request.url.encodedPath)
        val largest = pcmToWav(ByteArray(SpeechRecorder.MAX_BYTES))
        assertTrue(deepSeekAsrRequest(DeepSeekAsrConfig(), "test-key", largest).body!!.contentLength() <= 8 * 1024 * 1024)
        assertEquals(
            SpeechErrorCode.AUDIO,
            assertThrows(SpeechFailure::class.java) {
                deepSeekAsrRequest(DeepSeekAsrConfig(), "test-key", ByteArray(8 * 1024 * 1024 + 1))
            }.code,
        )
    }

    @Test fun transcriptTrimsTextAndRejectsErrorOrMissingText() {
        assertEquals("上海今天天气怎么样？", deepSeekAsrTranscript(JSONObject().put("text", "  上海今天天气怎么样？\n")))
        assertEquals("", deepSeekAsrTranscript(JSONObject().put("text", " \n")))
        val error = assertThrows(SpeechFailure::class.java) {
            deepSeekAsrTranscript(JSONObject().put("error", JSONObject().put("message", "private")))
        }
        assertEquals(SpeechErrorCode.SERVER, error.code)
        assertFalse(error.userMessage.contains("private"))
        assertEquals(
            SpeechErrorCode.PROTOCOL,
            assertThrows(SpeechFailure::class.java) {
                deepSeekAsrTranscript(JSONObject().put("duration_ms", 1200))
            }.code,
        )
    }
}
