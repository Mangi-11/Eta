package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.DeepSeekTtsConfig
import io.github.mangi.eta.data.model.TtsProvider
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSpeechSynthesisTest {
    @Test fun httpRequestUsesExpectedJsonBodyAndPath() {
        val request = deepSeekTtsRequest(
            DeepSeekTtsConfig(
                baseUrl = "https://example.invalid/proxy/",
                voice = " tide ",
                filterMarkdown = false,
            ),
            " test-key ",
            "你好，DeepSeek。",
        )
        assertEquals("https://example.invalid/proxy/v1/tools/tts", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals("你好，DeepSeek。", body.getString("text"))
        assertEquals("tide", body.getString("voice_id"))
        assertEquals("pcm", body.getString("format"))
        assertFalse(body.getBoolean("filter_markdown"))
    }

    @Test fun streamRequestUsesWssAndQueryParams() {
        val request = deepSeekTtsStreamRequest(
            DeepSeekTtsConfig(voice = " stella ", filterMarkdown = true),
            " test-key ",
        )
        assertEquals("https", request.url.scheme)
        assertEquals("/v1/tools/tts", request.url.encodedPath)
        assertEquals("stella", request.url.queryParameter("voice_id"))
        assertEquals("pcm", request.url.queryParameter("format"))
        assertEquals("true", request.url.queryParameter("filter_markdown"))
        assertEquals("Bearer test-key", request.header("Authorization"))
    }

    @Test fun billingCharCountCountsHanAsTwoAndAsciiAsOne() {
        assertEquals(2 + 2 + 5, deepSeekBillingCharCount("你好Hello"))
        assertTrue(supportsSpeechSession(TtsProvider.DEEPSEEK))
    }
}
