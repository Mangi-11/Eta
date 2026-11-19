package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechOssConfig
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody

internal object OssSpeechSigner {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun request(config: SpeechOssConfig, secrets: SpeechCredentials, key: String, method: String,
        body: RequestBody? = null, now: Instant = Instant.now()): Request {
        val time = timestamp.format(now)
        val scope = "${time.take(8)}/${config.region}/oss/aliyun_v4_request"
        val headers = sortedMapOf("x-oss-content-sha256" to "UNSIGNED-PAYLOAD", "x-oss-date" to time)
        body?.contentType()?.let { headers["content-type"] = it.toString() }
        if (method == "PUT") headers["x-oss-object-acl"] = "private"
        val canonical = "$method\n${encode("/${config.bucket}/$key", true)}\n\n" +
            headers.entries.joinToString("") { "${it.key}:${it.value}\n" } + "\n\nUNSIGNED-PAYLOAD"
        val signature = signature(secrets.ossAccessKeySecret, config.region, time, scope, canonical)
        return Request.Builder().url(objectUrl(config, key)).method(method, body).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Authorization", "OSS4-HMAC-SHA256 Credential=${secrets.ossAccessKeyId}/$scope, Signature=$signature")
        }.build()
    }

    fun readUrl(config: SpeechOssConfig, secrets: SpeechCredentials, key: String, now: Instant = Instant.now()): String {
        val time = timestamp.format(now)
        val scope = "${time.take(8)}/${config.region}/oss/aliyun_v4_request"
        val url = objectUrl(config, key)
        val query = sortedMapOf(
            "x-oss-signature-version" to "OSS4-HMAC-SHA256",
            "x-oss-credential" to "${secrets.ossAccessKeyId}/$scope",
            "x-oss-date" to time, "x-oss-expires" to "900", "x-oss-additional-headers" to "host",
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val canonical = "GET\n${encode("/${config.bucket}/$key", true)}\n$query\nhost:${url.host}\n\nhost\nUNSIGNED-PAYLOAD"
        return url.newBuilder().encodedQuery(query + "&x-oss-signature=" +
            signature(secrets.ossAccessKeySecret, config.region, time, scope, canonical)).build().toString()
    }

    fun signature(secret: String, region: String, time: String, scope: String, canonical: String): String {
        var key = ("aliyun_v4" + secret).toByteArray(Charsets.UTF_8)
        for (value in listOf(time.take(8), region, "oss", "aliyun_v4_request")) key = hmac(key, value)
        val hash = sha256(canonical)
        return hex(hmac(key, "OSS4-HMAC-SHA256\n$time\n$scope\n$hash"))
    }

    fun sha256(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun objectUrl(config: SpeechOssConfig, key: String) = speechBaseUrl(config.endpoint).toHttpUrl().let { endpoint ->
        if (endpoint.encodedPath != "/" || endpoint.port != 443) {
            throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_oss_endpoint_invalid, "OSS Endpoint 须为标准地域域名，不包含路径或端口"),
            )
        }
        endpoint.newBuilder().host("${config.bucket}.${endpoint.host}")
            .encodedPath("/${encode(key, true)}").build()
    }

    fun encode(value: String, slash: Boolean = false): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-_.~" || (slash && n == 47)) append(n.toChar())
            else append("%%%02X".format(n))
        }
    }
}
