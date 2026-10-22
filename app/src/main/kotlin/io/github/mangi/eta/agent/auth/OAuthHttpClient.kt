package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class OAuthHttpResponse(val status: Int, val json: JSONObject) {
    val successful: Boolean get() = status in 200..299
    val errorCode: String get() = json.optString("error").takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty()
    override fun toString(): String = "OAuthHttpResponse(status=$status)"
}

internal class OAuthHttpClient(private val client: OkHttpClient = defaultClient()) {

    fun get(url: String, controller: AgentRunController): OAuthHttpResponse =
        execute(Request.Builder().url(url).get().build(), controller)

    fun post(url: String, form: Map<String, String>, controller: AgentRunController): OAuthHttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        return execute(Request.Builder().url(url).post(body).build(), controller)
    }

    private fun execute(request: Request, controller: AgentRunController): OAuthHttpResponse {
        val call = client.newCall(request.newBuilder().header("Accept", "application/json").header("User-Agent", "Eta").build())
        val binding = controller.register(call::cancel)
        try {
            controller.throwIfCancelled()
            return call.execute().use { response ->
                controller.throwIfCancelled()
                val body = response.body.source()
                body.request(MAX_BYTES + 1)
                if (body.buffer.size > MAX_BYTES) throw ProviderAuthException("oauth_response_too_large", "授权服务返回的数据过大")
                val text = body.readUtf8()
                val parsed = try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (_: Exception) {
                    throw ProviderAuthException("oauth_invalid_response", "授权服务返回了无效响应（HTTP ${response.code}）")
                }
                OAuthHttpResponse(response.code, parsed)
            }
        } catch (_: IOException) {
            controller.throwIfCancelled()
            throw ProviderAuthException("oauth_network_error", "无法连接授权服务，请检查网络后重试")
        } finally {
            binding.close()
        }
    }

    private companion object {
        const val MAX_BYTES = 262_144L
        fun defaultClient(): OkHttpClient = AgentHttpClient.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
