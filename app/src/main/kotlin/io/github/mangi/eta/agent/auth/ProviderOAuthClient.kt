package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal interface ProviderOAuthClient {
    fun login(provider: OAuthProvider, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderCredential
    fun refresh(credential: ProviderCredential, controller: AgentRunController): ProviderCredential
    fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean?
}

internal class LiveProviderOAuthClient(
    private val http: OAuthHttpClient = OAuthHttpClient(),
    private val verifier: OidcTokenVerifier = OidcTokenVerifier(http),
    private val monotonicNow: () -> Long = System::nanoTime,
    private val waitForPoll: (AgentRunController, Long) -> Unit = { controller, millis -> controller.awaitRetryDelay(millis) },
    private val callbackReceiver: () -> LoopbackAuthorizationReceiver = { LoopbackAuthorizationReceiver() },
) : ProviderOAuthClient {
    override fun login(
        provider: OAuthProvider,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderCredential = when (provider) {
        OAuthProvider.OPENAI -> loginCodex(controller, onPrompt)
        OAuthProvider.XAI -> loginXai(controller, onPrompt)
    }

    private fun loginCodex(
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderCredential {
        val state = randomValue()
        val nonce = randomValue()
        val pkceVerifier = randomValue()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkceVerifier.toByteArray(Charsets.US_ASCII)),
        )
        val receiver = try { callbackReceiver() } catch (_: java.io.IOException) {
            throw ProviderAuthException("oauth_callback_unavailable", "无法启动 Codex 登录回调，请结束其他登录后重试")
        }
        return receiver.use {
            val url = "https://auth.openai.com/oauth/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", CodexOAuth.CLIENT_ID)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", receiver.redirectUri)
                .addQueryParameter("scope", CodexOAuth.SCOPE)
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("id_token_add_organizations", "true")
                .addQueryParameter("codex_cli_simplified_flow", "true")
                .addQueryParameter("originator", CodexOAuth.ORIGINATOR)
                .build()
            controller.throwIfCancelled()
            onPrompt(ProviderLoginPrompt(url.toString()))
            val code = receiver.awaitCode(state, controller)
            val response = http.post(OAuthProvider.OPENAI.tokenEndpoint, mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CodexOAuth.CLIENT_ID,
                "code" to code,
                "code_verifier" to pkceVerifier,
                "redirect_uri" to receiver.redirectUri,
            ), controller)
            requireSuccess(response)
            credential(response.json, OAuthProvider.OPENAI, CodexOAuth.CLIENT_ID, nonce, controller, null,
                defaultScopes = CodexOAuth.SCOPE.split(' ').toSet())
        }
    }

    private fun loginXai(controller: AgentRunController, onPrompt: (ProviderLoginPrompt) -> Unit): ProviderCredential {
        val authorization = http.post("https://auth.x.ai/oauth2/device/code", mapOf(
            "client_id" to XAI_CLIENT_ID,
            "scope" to XAI_SCOPES.joinToString(" "),
        ), controller)
        requireSuccess(authorization)
        val json = authorization.json
        val deviceCode = requiredString(json, "device_code")
        val userCode = requiredString(json, "user_code").also { requireProtocol(it.length <= 128) }
        val verificationUri = json.optString("verification_uri_complete").ifBlank { requiredString(json, "verification_uri") }
        val url = verificationUri.toHttpUrlOrNull()
        requireProtocol(url != null && url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("auth.x.ai", "accounts.x.ai", "grok.com", "www.grok.com"))
        val expiresIn = positiveSeconds(json, "expires_in")
        var intervalSeconds = if (json.has("interval")) positiveSeconds(json, "interval") else 5L
        val deadline = monotonicNow() + expiresIn * 1_000_000_000
        onPrompt(ProviderLoginPrompt(verificationUri, userCode))
        while (true) {
            val remaining = (deadline - monotonicNow()) / 1_000_000
            if (remaining <= 0) throw ProviderAuthException("oauth_login_expired", "验证码已过期，请重新登录")
            waitForPoll(controller, minOf(intervalSeconds * 1_000, remaining))
            controller.throwIfCancelled()
            if (monotonicNow() >= deadline) throw ProviderAuthException("oauth_login_expired", "验证码已过期，请重新登录")
            val response = try {
                http.post(OAuthProvider.XAI.tokenEndpoint, mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to XAI_CLIENT_ID,
                    "device_code" to deviceCode,
                ), controller)
            } catch (error: ProviderAuthException) {
                if (error.code != "oauth_network_error") throw error
                intervalSeconds = maxOf(intervalSeconds, (intervalSeconds * 2).coerceAtMost(120))
                continue
            }
            if (response.successful) {
                return credential(response.json, OAuthProvider.XAI, XAI_CLIENT_ID, null, controller, null,
                    defaultScopes = XAI_SCOPES)
            }
            when (response.errorCode) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5
                "access_denied" -> throw ProviderAuthException("oauth_access_denied", "登录未获授权")
                "expired_token" -> throw ProviderAuthException("oauth_login_expired", "验证码已过期，请重新登录")
                else -> requireSuccess(response)
            }
        }
    }

    override fun refresh(credential: ProviderCredential, controller: AgentRunController): ProviderCredential {
        val provider = OAuthProvider.fromAuthMode(credential.authMode)
        val form = mapOf("grant_type" to "refresh_token", "client_id" to credential.clientId,
            "refresh_token" to credential.refreshToken)
        val response = http.post(provider.tokenEndpoint, form, controller)
        requireSuccess(response)
        return credential(response.json, provider, credential.clientId, null, controller, credential, refreshing = true)
    }

    override fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean? {
        if (credential.refreshToken.isBlank()) return true
        val provider = OAuthProvider.fromAuthMode(credential.authMode)
        val endpoint = provider.revocationEndpoint ?: return null
        return http.post(endpoint, mapOf(
            "token" to credential.refreshToken,
            "token_type_hint" to "refresh_token",
            "client_id" to credential.clientId,
        ), controller).successful
    }

    private fun credential(
        json: JSONObject,
        provider: OAuthProvider,
        clientId: String,
        nonce: String?,
        controller: AgentRunController,
        previous: ProviderCredential?,
        refreshing: Boolean = false,
        defaultScopes: Set<String> = emptySet(),
    ): ProviderCredential {
        val accessToken = requiredString(json, "access_token")
        requireProtocol(json.optString("token_type").equals("Bearer", ignoreCase = true))
        val expiresIn = positiveSeconds(json, "expires_in")
        val idToken = json.optString("id_token").takeIf { it.isNotBlank() && it != "null" }
        val identity = if (idToken != null) {
            verifier.verify(idToken, provider, clientId, nonce, controller)
        } else {
            requireProtocol(refreshing && previous != null)
            VerifiedProviderIdentity(requireNotNull(previous!!.subject), previous.email, previous.displayName)
        }
        if (previous?.subject != null && previous.subject != identity.subject) {
            throw ProviderAuthException("oauth_account_mismatch", "登录账号与原账号不一致，请新建服务商连接")
        }
        val scope = json.optString("scope").takeIf { it.isNotBlank() && it != "null" }
        val scopes = scope?.split(' ')?.filter(String::isNotBlank)?.toSet()
            ?: if (refreshing) previous!!.scopes else defaultScopes
        val accountId = if (provider == OAuthProvider.OPENAI) CodexOAuth.accountId(accessToken) else null
        if (refreshing && previous?.accountId != accountId) {
            throw ProviderAuthException("oauth_account_mismatch", "刷新后的账号上下文不一致，请重新登录")
        }
        return ProviderCredential(
            authMode = provider.authMode,
            clientId = clientId,
            subject = identity.subject,
            email = identity.email,
            displayName = identity.name,
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" }
                ?: if (refreshing) previous!!.refreshToken else "",
            idToken = idToken ?: previous?.idToken.orEmpty(),
            accountId = accountId,
            expiresAt = System.currentTimeMillis() + expiresIn * 1_000,
            scopes = scopes,
        )
    }

    private fun requireSuccess(response: OAuthHttpResponse) {
        if (response.successful) return
        if (response.errorCode in TERMINAL_REFRESH_ERRORS) {
            throw ProviderAuthException("oauth_session_expired", "登录已失效，请重新登录")
        }
        if (response.errorCode == "invalid_client") {
            throw ProviderAuthException("oauth_client_rejected", "授权服务未接受此客户端")
        }
        throw ProviderAuthException("oauth_request_failed", "授权请求失败（HTTP ${response.status}），请稍后重试")
    }

    private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun requiredString(json: JSONObject, key: String): String = json.optString(key)
        .takeIf { it.isNotBlank() && it != "null" && it.length <= 65_536 }
        ?: throw ProviderAuthException("oauth_invalid_response", "授权服务返回的数据不完整")

    private fun positiveSeconds(json: JSONObject, key: String): Long = json.optLong(key, -1).also {
        requireProtocol(it in 1..31_536_000)
    }

    private fun requireProtocol(condition: Boolean) {
        if (!condition) throw ProviderAuthException("oauth_invalid_response", "授权服务返回的数据无效")
    }

    private companion object {
        const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        val XAI_SCOPES = linkedSetOf("openid", "profile", "email", "offline_access", "grok-cli:access", "api:access")
        val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused")
    }
}
