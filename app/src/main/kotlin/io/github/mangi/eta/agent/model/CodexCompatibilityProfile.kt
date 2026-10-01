package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * The small, deliberately explicit compatibility surface used by the ChatGPT
 * subscription/Codex route.
 *
 * This is not the OpenAI Platform API-key route.  Keeping its endpoints,
 * client identity and headers together makes it harder for a later change to
 * accidentally send a subscription token through an ordinary provider.
 */
internal object CodexCompatibilityProfile {
    const val AUTH_BASE_URL = "https://auth.openai.com"
    const val CODEX_RESPONSES_BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val DEVICE_USER_CODE_URL = "$AUTH_BASE_URL/api/accounts/deviceauth/usercode"
    const val DEVICE_TOKEN_URL = "$AUTH_BASE_URL/api/accounts/deviceauth/token"
    const val TOKEN_URL = "$AUTH_BASE_URL/oauth/token"
    const val DEVICE_VERIFICATION_URL = "$AUTH_BASE_URL/codex/device"
    const val REDIRECT_URI = "$AUTH_BASE_URL/deviceauth/callback"
    const val CODEX_MODELS_URL = "$CODEX_RESPONSES_BASE_URL/models"
    /**
     * Catalog compatibility baseline sent to the Codex backend. This is
     * intentionally independent from Eta's app version and is not a claim
     * that Eta embeds or identifies itself as this Codex CLI release.
     */
    const val CODEX_CATALOG_COMPATIBILITY_VERSION = "0.153.0"

    // Public client id used by the Codex CLI device-login flow.
    const val PUBLIC_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    const val AUTH_MODE = "codex_subscription"
    const val API_KEY_AUTH_MODE = "api_key"
    /** Identity used by the built-in Eta client at the official endpoint. */
    const val ORIGINATOR = "eta"
    const val USER_AGENT = "Eta/3.0.5"
    /** Compatibility identity is opt-in and only for third-party endpoints. */
    const val COMPATIBILITY_ORIGINATOR = "codex_cli_rs"
    const val COMPATIBILITY_USER_AGENT = "codex_cli_rs/0.0.0 (Eta)"
    const val REFRESH_SKEW_MS = 120_000L

    enum class Identity {
        ETA,
        CODEX_CLI_COMPATIBILITY,
    }

    /**
     * Claims extracted from the access-token JWT.  The token itself is never
     * retained here; callers should keep it only in the request-local scope.
     */
    data class JwtClaims(
        val accountId: String? = null,
        val residency: String? = null,
        val expiresAtEpochSeconds: Long? = null,
    )

    fun decodeJwtClaims(accessToken: String): JwtClaims? {
        val parts = accessToken.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = String(decodeBase64Url(parts[1]), Charsets.UTF_8)
            val root = JSONObject(payload)
            val auth = root.optJSONObject("https://api.openai.com/auth")
            JwtClaims(
                accountId = firstNonBlank(
                    auth?.optString("chatgpt_account_id"),
                    auth?.optString("account_id"),
                    root.optString("chatgpt_account_id"),
                    root.optString("account_id"),
                ),
                residency = firstNonBlank(
                    auth?.optString("chatgpt_data_residency"),
                    auth?.optString("chatgpt_compute_residency"),
                    root.optString("chatgpt_data_residency"),
                    root.optString("chatgpt_compute_residency"),
                ),
                expiresAtEpochSeconds = root.optLongOrNull("exp"),
            )
        }.getOrNull()
    }

    /** Preserve the account context saved at login when a refreshed JWT omits a claim. */
    fun claimsForCredentials(credentials: CodexCredentials): JwtClaims {
        val decoded = decodeJwtClaims(credentials.accessToken)
        return JwtClaims(
            accountId = decoded?.accountId ?: credentials.accountId,
            residency = decoded?.residency ?: credentials.residency,
            expiresAtEpochSeconds = decoded?.expiresAtEpochSeconds,
        )
    }

    /**
     * Dynamic identity headers required by the Codex backend.  Authorization
     * is intentionally not returned as a custom header: the Responses client
     * adds it from its request-local apiKey override, and the persisted
     * runtime config never contains that override.
     */
    fun requestHeaders(
        claims: JwtClaims?,
        sessionId: String,
        requestId: String = UUID.randomUUID().toString(),
        identity: Identity = Identity.ETA,
    ): List<CustomHeader> = buildList {
        addAll(catalogHeaders(claims, identity))
        // These request-scoped identifiers belong to Responses requests. The
        // /models catalog follows Hermes' smaller header set and must not get
        // synthetic session/request headers.
        add(CustomHeader("session_id", sessionId))
        add(CustomHeader("x-client-request-id", requestId))
    }

    /**
     * Headers shared by the Codex catalog and Responses endpoints.
     *
     * Hermes sends only identity plus the account/residency claims to
     * `/models`; keeping this helper separate prevents catalog requests from
     * accidentally inheriting conversation-scoped headers.
     */
    fun catalogHeaders(
        claims: JwtClaims?,
        identity: Identity = Identity.ETA,
    ): List<CustomHeader> = buildList {
        add(
            CustomHeader(
                "User-Agent",
                if (identity == Identity.ETA) USER_AGENT else COMPATIBILITY_USER_AGENT,
            ),
        )
        add(
            CustomHeader(
                "originator",
                if (identity == Identity.ETA) ORIGINATOR else COMPATIBILITY_ORIGINATOR,
            ),
        )
        claims?.accountId?.takeIf(String::isNotBlank)?.let {
            add(CustomHeader("ChatGPT-Account-ID", it))
        }
        claims?.residency?.takeIf(String::isNotBlank)?.let {
            add(CustomHeader("x-openai-internal-codex-residency", it))
        }
    }

    fun shouldRefresh(expiresAtEpochMillis: Long?, nowEpochMillis: Long): Boolean =
        expiresAtEpochMillis == null || expiresAtEpochMillis <= nowEpochMillis + REFRESH_SKEW_MS

    /** Refresh failures that invalidate the refresh token require an explicit login again. */
    fun isReauthenticationFailure(httpCode: Int, responseBody: String): Boolean {
        val body = responseBody.lowercase()
        return body.contains("invalid_grant") ||
            body.contains("invalid grant") ||
            body.contains("reused") ||
            body.contains("invalidated") ||
            body.contains("refresh token expired") ||
            httpCode == 401 ||
            httpCode == 403
    }

    /** Retry one unauthorized Codex attempt after a serialized forced refresh. */
    fun <T> withUnauthorizedRetry(
        initialAccessToken: String,
        execute: (accessToken: String) -> T,
        refresh: (failedAccessToken: String) -> String,
    ): T {
        return try {
            execute(initialAccessToken)
        } catch (failure: AgentModelFailure) {
            if (failure.code != "HTTP_401") throw failure
            execute(refresh(initialAccessToken))
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun decodeBase64Url(segment: String): ByteArray {
        val padding = (4 - segment.length % 4) % 4
        return Base64.getUrlDecoder().decode(segment + "=".repeat(padding))
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            null -> null
            else -> null
        }
    }
}
