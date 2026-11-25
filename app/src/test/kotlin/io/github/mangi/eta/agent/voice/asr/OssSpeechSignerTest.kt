package io.github.mangi.eta.agent.voice.asr

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechOssConfig
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class OssSpeechSignerTest {
    private val config = SpeechOssConfig(region = "cn-hangzhou", endpoint = "https://oss-cn-hangzhou.aliyuncs.com", bucket = "examplebucket")
    private val credentials = SpeechCredentials(ossAccessKeyId = "test-key", ossAccessKeySecret = "test-secret")
    private val now = Instant.parse("2025-04-11T06:41:24Z")

    @Test fun canonicalHashMatchesPublishedOssV4HeaderExample() {
        val canonical = "PUT\n/examplebucket/exampleobject\n\n" +
            "content-disposition:attachment\ncontent-length:3\ncontent-md5:ICy5YqxZB1uWSwcVLSNLcA==\n" +
            "content-type:text/plain\nx-oss-content-sha256:UNSIGNED-PAYLOAD\nx-oss-date:20250411T064124Z\n\n" +
            "content-disposition;content-length\nUNSIGNED-PAYLOAD"
        assertEquals("c46d96390bdbc2d739ac9363293ae9d710b14e48081fcb22cd8ad54b63136eca", OssSpeechSigner.sha256(canonical))
        assertEquals("d3694c2dfc5371ee6acd35e88c4871ac95a7ba01d3a2f476768fe61218590097",
            OssSpeechSigner.signature("yourAccessKeySecret", "cn-hangzhou", "20250411T064124Z",
                "20250411/cn-hangzhou/oss/aliyun_v4_request", canonical))
    }

    @Test fun uploadIsPrivateAndAuthorizationIsNotAddedToReadUrl() {
        val request = OssSpeechSigner.request(config, credentials, "录音/a b.wav", "PUT", byteArrayOf(1, 2).toRequestBody("audio/wav".toMediaType()), now)
        assertEquals("private", request.header("x-oss-object-acl"))
        assertEquals("20250411T064124Z", request.header("x-oss-date"))
        assertTrue(request.header("Authorization")!!.contains("20250411/cn-hangzhou/oss/aliyun_v4_request"))
        assertEquals("/%E5%BD%95%E9%9F%B3/a%20b.wav", request.url.encodedPath)
        val url = okhttp3.HttpUrl.Companion.run { OssSpeechSigner.readUrl(config, credentials, "录音/a b.wav", now).toHttpUrl() }
        assertEquals("900", url.queryParameter("x-oss-expires"))
        assertEquals("test-key/20250411/cn-hangzhou/oss/aliyun_v4_request", url.queryParameter("x-oss-credential"))
        assertFalse(url.toString().contains("test-secret"))
        assertEquals(64, url.queryParameter("x-oss-signature")!!.length)
    }

    @Test fun canonicalEncodingUsesUtf8AndPreservesOnlyPathSlashes() {
        assertEquals("a%20b%2Fc%2B%25", OssSpeechSigner.encode("a b/c+%"))
        assertEquals("a%20b/c%2B%25", OssSpeechSigner.encode("a b/c+%", slash = true))
    }
}
