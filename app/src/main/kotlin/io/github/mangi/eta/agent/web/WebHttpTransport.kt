package io.github.mangi.eta.agent.web

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal class WebRequestException(val code: String, message: String, val status: Int? = null) : IOException(message)

internal data class WebHttpResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val bytes: ByteArray,
    val truncated: Boolean,
)

/** 独立匿名 GET 请求，不继承 WebView 登录状态；关闭只取消本次运行拥有的请求。 */
internal class WebHttpTransport(baseClient: OkHttpClient) : Closeable {
    private val client = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(okhttp3.Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun ensureActive() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw WebRequestException("WEB_CANCELLED", "网页请求已取消")
    }

    fun get(rawUrl: String, maxBytes: Int = MAX_RESPONSE_BYTES, timeoutSeconds: Long = 30): WebHttpResponse {
        require(maxBytes in 1..MAX_RESPONSE_BYTES)
        require(timeoutSeconds in 1..30)
        val initial = validateUrl(rawUrl)
        var url = initial
        val visited = hashSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            ensureActive()
            if (!visited.add(url.toString())) throw WebRequestException("WEB_REDIRECT_LOOP", "网页重定向形成循环")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw WebRequestException("WEB_TIMEOUT", "网页请求超时")
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Eta/1.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,text/markdown,application/json;q=0.9,*/*;q=0.1")
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            activeCalls += call
            if (closed.get()) call.cancel()
            try {
                call.execute().use { response ->
                    ensureActive()
                    if (response.code in REDIRECT_STATUS) {
                        if (redirectCount == MAX_REDIRECTS) throw WebRequestException("WEB_REDIRECT_LIMIT", "网页重定向次数超过限制")
                        val location = response.header("Location")
                            ?: throw WebRequestException("WEB_REDIRECT_INVALID", "网页重定向缺少目标地址")
                        val resolved = url.resolve(location)
                            ?: throw WebRequestException("WEB_REDIRECT_INVALID", "网页重定向地址无效")
                        url = validateUrl(resolved.toString())
                    } else {
                        val status = response.code
                        // 错误响应不读取正文，避免认证信息和服务器回显进入日志或错误说明。
                        if (!response.isSuccessful) return WebHttpResponse(initial.toString(), url.toString(), status, response.header("Content-Type"), byteArrayOf(), false)
                        val body = response.body
                        val output = ByteArrayOutputStream(minOf(maxBytes, 16_384))
                        var truncated = false
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                ensureActive()
                                val capacity = maxBytes - output.size()
                                val count = input.read(buffer, 0, minOf(buffer.size, capacity + 1))
                                if (count < 0) break
                                if (count > capacity) {
                                    output.write(buffer, 0, capacity)
                                    truncated = true
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        ensureActive()
                        return WebHttpResponse(initial.toString(), url.toString(), status, response.header("Content-Type"), output.toByteArray(), truncated)
                    }
                }
            } catch (failure: WebRequestException) {
                throw failure
            } catch (_: InterruptedIOException) {
                ensureActive()
                throw WebRequestException("WEB_TIMEOUT", "网页连接或读取超时")
            } catch (_: ProtocolException) {
                ensureActive()
                throw WebRequestException("WEB_PROTOCOL_ERROR", "服务器返回了无法处理的 HTTP 响应")
            } catch (_: IOException) {
                ensureActive()
                throw WebRequestException("WEB_NETWORK_ERROR", "无法完成网页请求，请检查网络、证书及相关网络授权")
            } finally {
                activeCalls -= call
            }
        }
        throw WebRequestException("WEB_REDIRECT_LIMIT", "网页重定向次数超过限制")
    }

    override fun close() {
        closed.set(true)
        activeCalls.forEach(Call::cancel)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)

        fun validateUrl(raw: String): HttpUrl {
            if (raw.isBlank() || raw.length > 8192 || raw.any { it.isISOControl() }) {
                throw WebRequestException("INVALID_URL", "网址为空、过长或包含无效字符")
            }
            val url = raw.toHttpUrlOrNull() ?: throw WebRequestException("INVALID_URL", "只支持完整的 HTTP(S) 网址")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw WebRequestException("URL_CREDENTIALS_UNSUPPORTED", "此工具不接受包含用户名或密码的网址")
            }
            return url.newBuilder().fragment(null).build().also {
                if (it.toString().length > 8192) throw WebRequestException("INVALID_URL", "编码后的网址超过长度限制")
            }
        }
    }
}
