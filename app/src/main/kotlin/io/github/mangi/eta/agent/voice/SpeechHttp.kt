package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal object SpeechHttp {
    val client = AgentHttpClient.client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    fun jsonRequest(url: String, apiKey: String, body: JSONObject): Request.Builder = Request.Builder()
        .url(url).header("Authorization", "Bearer ${apiKey.trim()}")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    suspend fun json(request: Request): JSONObject = read(request) { response ->
        val source = response.body.source()
        if (source.request(MAX_JSON_BYTES + 1)) {
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_http_response_too_large, "语音服务响应过大"),
            )
        }
        JSONObject(source.readUtf8())
    }

    /** 消费响应的整个周期都在取消回调保护内，避免只取消建连却遗留阻塞读。 */
    suspend fun <T> read(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        checkStatus(response.code)
                        consume(response)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    fun checkStatus(status: Int) {
        if (status in 200..299) return
        throw when (status) {
            401, 403 -> SpeechFailure(
                SpeechErrorCode.AUTHENTICATION,
                AppStrings.get(R.string.voice_i18n_http_auth_failed, "语音服务认证失败，请检查凭据和服务权限"),
            )
            429 -> SpeechFailure(
                SpeechErrorCode.RATE_LIMITED,
                AppStrings.get(R.string.voice_i18n_http_rate_limited, "语音服务请求过于频繁，请稍后重试"),
            )
            else -> SpeechFailure(
                SpeechErrorCode.SERVER,
                AppStrings.get(R.string.voice_i18n_http_request_failed, "语音服务请求失败（HTTP %1\$d）", status),
            )
        }
    }

    const val MAX_JSON_BYTES = 2L * 1024 * 1024
}

internal fun Request.Builder.doubaoHeaders(config: DoubaoSpeechConfig, key: String, resource: String): Request.Builder {
    if (config.legacyAuth) {
        header("X-Api-App-Key", config.appId.trim())
        header("X-Api-Access-Key", key.trim())
    } else header("X-Api-Key", key.trim())
    return header("X-Api-Resource-Id", config.resourceId.trim().ifEmpty { resource })
        .header("X-Api-Request-Id", UUID.randomUUID().toString())
        .header("X-Api-Connect-Id", UUID.randomUUID().toString())
}
