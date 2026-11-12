package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.MimoAsrConfig
import io.github.mangi.eta.data.model.MimoAsrLanguage
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.Base64
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MimoSpeechRecognitionTest {
    @Test fun requestUsesDedicatedModelAndOneAudioMessageWithoutChatInstructions() {
        val wav = pcmToWav(byteArrayOf(0, 1, 2, 3))
        val request = mimoAsrRequest(MimoAsrConfig(), " test-key ", wav)
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertNull(request.url.query)
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("model", "messages", "asr_options", "stream"), body.keys().asSequence().toSet())
        assertEquals("mimo-v2.5-asr", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("auto", body.getJSONObject("asr_options").getString("language"))
        assertEquals(setOf("language"), body.getJSONObject("asr_options").keys().asSequence().toSet())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("input_audio", content.getJSONObject(0).getString("type"))
        val encoded = content.getJSONObject(0).getJSONObject("input_audio").getString("data")
        assertTrue(encoded.startsWith("data:audio/wav;base64,"))
        assertArrayEquals(wav, Base64.getDecoder().decode(encoded.substringAfter(',')))
    }

    @Test fun requestPreservesGatewayPrefixAndMapsOnlySupportedLanguages() {
        for ((language, expected) in listOf(
            MimoAsrLanguage.AUTO to "auto",
            MimoAsrLanguage.CHINESE to "zh",
            MimoAsrLanguage.ENGLISH to "en",
        )) {
            val request = mimoAsrRequest(
                MimoAsrConfig(baseUrl = " https://example.invalid/proxy/v1/ ", language = language),
                "test-key", pcmToWav(byteArrayOf(0, 0)),
            )
            assertEquals("/proxy/v1/chat/completions", request.url.encodedPath)
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(expected, body.getJSONObject("asr_options").getString("language"))
        }
    }

    @Test fun recordedAudioFitsMimoAndProviderLimitIsCheckedBeforeEncoding() {
        val largest = pcmToWav(ByteArray(SpeechRecorder.MAX_BYTES))
        val request = mimoAsrRequest(MimoAsrConfig(), "test-key", largest)
        assertTrue(request.body!!.contentLength() < 10_000_000)
        assertEquals(SpeechErrorCode.AUDIO, assertThrows(SpeechFailure::class.java) {
            mimoAsrRequest(MimoAsrConfig(), "test-key", ByteArray(7_500_001))
        }.code)
    }

    @Test fun onlyCompletedTextIsAcceptedAndBlankTextIsLeftToTheInputController() {
        assertEquals("请打开设置。", mimoAsrTranscript(response("  请打开设置。\n")))
        assertEquals("", mimoAsrTranscript(response(" \n\t")))
        for (reason in listOf("length", "content_filter")) {
            assertEquals(SpeechErrorCode.SERVER, assertThrows(SpeechFailure::class.java) {
                mimoAsrTranscript(response("未完成的转写", reason))
            }.code)
        }
        for (reason in listOf("", "tool_calls", "unknown")) {
            assertEquals(SpeechErrorCode.PROTOCOL, assertThrows(SpeechFailure::class.java) {
                mimoAsrTranscript(response("未完成的转写", reason))
            }.code)
        }
    }

    @Test fun malformedOrErrorResponsesNeverBecomeTranscriptOrExposeResponseData() {
        for (payload in listOf(
            JSONObject(),
            JSONObject().put("choices", JSONArray()),
            response(JSONObject.NULL),
            response(42),
            response(JSONArray().put("private response")),
            response("private response").apply { getJSONArray("choices").getJSONObject(0).remove("finish_reason") },
        )) {
            assertEquals(SpeechErrorCode.PROTOCOL, assertThrows(SpeechFailure::class.java) {
                mimoAsrTranscript(payload)
            }.code)
        }
        val error = assertThrows(SpeechFailure::class.java) {
            mimoAsrTranscript(response("private transcript").put(
                "error", JSONObject().put("message", "private response"),
            ))
        }
        assertEquals(SpeechErrorCode.SERVER, error.code)
        assertFalse(error.userMessage.contains("private"))
        assertFalse(error.toString().contains("private"))
    }

    @Test fun validationUsesOnlyMimoCredentialsAndConfiguration() {
        val settings = SpeechSettings(
            asr = AsrProvider.MIMO,
            qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused-url"),
            language = "ja",
        )
        validateSpeechSettings(settings, SpeechCredentials(mimoAsr = "test-key"), synthesis = false)
        assertEquals(SpeechErrorCode.CONFIGURATION, assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(settings, SpeechCredentials(qwenAsr = "test-key"), synthesis = false)
        }.code)
        for (url in listOf("http://example.invalid/v1", "https://key@example.invalid/v1", "https://example.invalid/v1?key=secret")) {
            assertEquals(SpeechErrorCode.CONFIGURATION, assertThrows(SpeechFailure::class.java) {
                validateSpeechSettings(settings.copy(mimoAsr = MimoAsrConfig(baseUrl = url)),
                    SpeechCredentials(mimoAsr = "test-key"), synthesis = false)
            }.code)
        }
    }

    private fun response(content: Any, finishReason: String = "stop") = JSONObject().put(
        "choices", JSONArray().put(JSONObject()
            .put("finish_reason", finishReason)
            .put("message", JSONObject().put("role", "assistant").put("content", content))),
    )
}
