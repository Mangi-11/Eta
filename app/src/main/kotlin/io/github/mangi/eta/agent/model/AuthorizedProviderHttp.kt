package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 凭据仅在请求发送前解析；401 恢复止于响应头，不重放已经开始的模型流。 */
internal object AuthorizedProviderHttp {
    fun <T> execute(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        request: Request,
        controller: AgentRunController,
        accessToken: (String, String, AgentRunController, String?) -> String =
            { id, mode, run, rejected -> ProviderCredentialRepository.accessToken(id, mode, run, rejected) },
        consume: (Response) -> T,
    ): T {
        val profile = ProviderConnectionProfile.forAuthMode(authMode)
        profile?.requireRequestTarget(request.url)
        val httpClient = if (profile == null) client else client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        var rejectedToken: String? = null
        var refreshed = false
        while (true) {
            controller.throwIfCancelled()
            val token = profile?.let { accessToken(providerId, authMode, controller, rejectedToken) }
            val authorized = if (token == null) request else request.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val call = httpClient.newCall(authorized)
            val binding = controller.register(call::cancel)
            try {
                controller.throwIfCancelled()
                call.execute().use { response ->
                    controller.throwIfCancelled()
                    if (profile != null && response.code == 401 && !refreshed) {
                        rejectedToken = token
                        refreshed = true
                    } else {
                        if (profile != null && response.code == 401) {
                            val failure = AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                            if (failure.code != "HTTP_401") throw failure
                            throw AgentModelFailure(
                                "OAUTH_UNAUTHORIZED", false, "订阅登录已失效，请重新登录服务商账户。",
                            )
                        }
                        return consume(response)
                    }
                }
            } catch (failure: Throwable) {
                controller.throwIfCancelled()
                throw failure
            } finally {
                binding.close()
            }
        }
    }
}
