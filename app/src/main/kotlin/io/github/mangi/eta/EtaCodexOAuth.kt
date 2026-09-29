package io.github.mangi.eta

import android.content.Context
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.agent.model.CodexCredentialStore
import io.github.mangi.eta.agent.model.CodexCredentials
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.toSafeLogToken
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/**
 * Device-login and refresh coordinator for the built-in Codex subscription
 * provider.  It owns no UI state and never exposes tokens to persistence other
 * than [CodexCredentialStore].
 */
internal object CodexOAuthManager {
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
    private const val MAX_DEVICE_LOGIN_MILLIS = 15 * 60 * 1000L
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val refreshLock = ReentrantLock()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun hasStoredCredentials(): Boolean = readCredentials() != null

    fun readCredentials(): CodexCredentials? = appContext?.let { context ->
        val result = CodexCredentialStore(context).readDetailed()
        logCredentialRead(result)
        result.credentials
    }?.takeIf { it.accessToken.isNotBlank() }

    fun logout() {
        appContext?.let { CodexCredentialStore(it).clear() }
    }

    /** Return a fresh access token, with one in-process refresh flight at a time. */
    fun requireCredentials(): CodexCredentials {
        val store = store()
        val current = readStoreCredentials(store)
        if (current != null && current.isUsable()) return current

        refreshLock.lock()
        try {
            // Another Eta process may have completed a refresh while we
            // waited.  Re-read and keep the file lock for the network call
            // plus the atomic write; refresh tokens may rotate on every use.
            return store.withExclusiveLock {
                val latestResult = store.readLockedDetailed()
                logCredentialRead(latestResult)
                val latest = latestResult.credentials
                if (latest != null && latest.isUsable()) return@withExclusiveLock latest
                val refreshToken = latest?.refreshToken.orEmpty()
                if (refreshToken.isBlank()) {
                    throw CodexReauthenticationRequiredException("OpenAI Codex 需要重新登录")
                }
                refresh(store, latest, refreshToken)
            }
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * A backend 401 is not enough to discard credentials: another Eta process
     * may have rotated the token, or the access token may have been revoked
     * while its refresh token remains usable.  Re-read while holding the same
     * cross-process lock used by refresh, then either adopt the newer token or
     * force exactly one refresh of the observed token.
     */
    fun forceRefreshAfterUnauthorized(failedAccessToken: String): CodexCredentials {
        val store = store()
        refreshLock.lock()
        try {
            return store.withExclusiveLock {
                val latestResult = store.readLockedDetailed()
                logCredentialRead(latestResult)
                val latest = latestResult.credentials
                codexCredentialsAfterUnauthorized(
                    latest = latest,
                    failedAccessToken = failedAccessToken,
                ) { current, refreshToken ->
                    refresh(store, current, refreshToken)
                }
            }
        } finally {
            refreshLock.unlock()
        }
    }

    suspend fun requestDeviceCode(): CodexDeviceCode = withContext(Dispatchers.IO) {
        val body = JSONObject().put("client_id", CodexCompatibilityProfile.PUBLIC_CLIENT_ID)
        val request = Request.Builder()
            .url(CodexCompatibilityProfile.DEVICE_USER_CODE_URL)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", CodexCompatibilityProfile.USER_AGENT)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeOAuthRequest(request, CodexOAuthStage.DEVICE_REQUEST).use { response ->
            val text = CodexOAuthResponseBody.read(response, CodexOAuthStage.DEVICE_REQUEST)
            if (response.code == 429) {
                throw CodexRateLimitedException(
                    message = "OpenAI 设备码请求受到限流，请稍后重试",
                    stage = CodexOAuthStage.DEVICE_REQUEST,
                    httpStatus = response.code,
                )
            }
            if (response.code != 200) {
                throw CodexOAuthHttpException(
                    message = "获取 OpenAI 设备码失败（HTTP ${response.code}）",
                    stage = CodexOAuthStage.DEVICE_REQUEST,
                    errorCode = "device_code_request_http",
                    httpStatus = response.code,
                )
            }
            val json = parseJsonObject(
                text = text,
                stage = CodexOAuthStage.DEVICE_REQUEST,
                errorCode = "device_code_request_invalid_response",
                message = "OpenAI 设备码响应无法解析",
            )
            CodexDeviceCode(
                userCode = json.optString("user_code").ifBlank { json.optString("userCode") },
                deviceAuthId = json.optString("device_auth_id").ifBlank { json.optString("deviceAuthId") },
                intervalSeconds = CodexDeviceFlowProtocol.normalizedPollIntervalSeconds(
                    json.optLong("interval", DEFAULT_POLL_INTERVAL_SECONDS),
                ),
                expiresInSeconds = json.optLong("expires_in", 900L).coerceAtLeast(60L),
                verificationUrl = json.optString("verification_url")
                    .ifBlank { CodexCompatibilityProfile.DEVICE_VERIFICATION_URL },
            ).also {
                if (it.userCode.isBlank() || it.deviceAuthId.isBlank()) {
                    throw CodexOAuthHttpException(
                        message = "OpenAI 设备码响应缺少必要字段",
                        stage = CodexOAuthStage.DEVICE_REQUEST,
                        errorCode = "device_code_request_incomplete",
                        httpStatus = response.code,
                    )
                }
            }
        }
    }

    suspend fun completeDeviceCodeLogin(deviceCode: CodexDeviceCode): CodexCredentials =
        withContext(Dispatchers.IO) {
            val lifetimeMillis = deviceCode.expiresInSeconds
                .coerceIn(60L, MAX_DEVICE_LOGIN_MILLIS / 1000L) * 1000L
            val deadline = System.currentTimeMillis() +
                minOf(MAX_DEVICE_LOGIN_MILLIS, lifetimeMillis)
            val pollDelayMillis = CodexDeviceFlowProtocol.normalizedPollIntervalSeconds(
                deviceCode.intervalSeconds,
            ) * 1000L
            while (System.currentTimeMillis() < deadline) {
                // Hermes waits before the first request.  Apart from matching
                // the upstream flow, this avoids turning a freshly-issued
                // device code into an immediate 403/404 burst.
                val remainingMillis = deadline - System.currentTimeMillis()
                if (remainingMillis <= 0L) break
                delay(minOf(pollDelayMillis, remainingMillis))
                // OkHttp's synchronous execute is not itself cancellable.  Do
                // not exchange or persist a token if the user canceled while
                // that poll was in flight.
                currentCoroutineContext().ensureActive()
                val result = pollDeviceCode(deviceCode)
                currentCoroutineContext().ensureActive()
                if (result != null) {
                    val credentials = exchangeAuthorizationCode(result.authorizationCode, result.codeVerifier)
                    currentCoroutineContext().ensureActive()
                    return@withContext store().writeAndReadBack(credentials)
                }
            }
            throw CodexOAuthHttpException(
                message = "OpenAI 设备码已过期，请重新获取",
                stage = CodexOAuthStage.DEVICE_POLL,
                errorCode = "device_code_expired",
            )
        }

    private fun refresh(
        store: CodexCredentialStore,
        previous: CodexCredentials?,
        refreshToken: String,
    ): CodexCredentials {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CodexCompatibilityProfile.PUBLIC_CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(CodexCompatibilityProfile.TOKEN_URL)
            .header("Accept", "application/json")
            .header("User-Agent", CodexCompatibilityProfile.USER_AGENT)
            .post(form)
            .build()
        executeOAuthRequest(request, CodexOAuthStage.TOKEN_REFRESH).use { response ->
            val text = CodexOAuthResponseBody.read(response, CodexOAuthStage.TOKEN_REFRESH)
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    // A rate limit must not destroy a still-recoverable refresh token.
                    throw CodexRefreshRateLimitedException(
                        message = "OpenAI 刷新令牌请求受限，请稍后重试",
                        httpStatus = response.code,
                    )
                }
                if (CodexCompatibilityProfile.isReauthenticationFailure(response.code, text)) {
                    store.clearLocked()
                    throw CodexReauthenticationRequiredException(
                        message = "OpenAI 登录已失效，请重新登录",
                        stage = CodexOAuthStage.TOKEN_REFRESH,
                        errorCode = "refresh_reauthentication_required",
                        httpStatus = response.code,
                    )
                }
                throw CodexOAuthHttpException(
                    message = "OpenAI 刷新令牌失败（HTTP ${response.code}）",
                    stage = CodexOAuthStage.TOKEN_REFRESH,
                    errorCode = "refresh_http_error",
                    httpStatus = response.code,
                )
            }
            val json = parseJsonObject(
                text = text,
                stage = CodexOAuthStage.TOKEN_REFRESH,
                errorCode = "refresh_invalid_response",
                message = "OpenAI 刷新响应无法解析",
            )
            val responseError = json.optString("error")
            if (responseError.isNotBlank() &&
                CodexCompatibilityProfile.isReauthenticationFailure(response.code, responseError)
            ) {
                // Some compatible token endpoints return a JSON OAuth error with
                // HTTP 200.  Treat it exactly like the corresponding non-2xx
                // invalid_grant response so a rotated/revoked refresh token is
                // not retried forever.
                store.clearLocked()
                throw CodexReauthenticationRequiredException(
                    message = "OpenAI 登录已失效，请重新登录",
                    stage = CodexOAuthStage.TOKEN_REFRESH,
                    errorCode = "refresh_reauthentication_required",
                    httpStatus = response.code,
                )
            }
            val accessToken = json.optString("access_token")
            if (accessToken.isBlank()) {
                throw CodexOAuthHttpException(
                    message = "OpenAI 刷新响应缺少访问令牌",
                    stage = CodexOAuthStage.TOKEN_REFRESH,
                    errorCode = "refresh_missing_access_token",
                    httpStatus = response.code,
                )
            }
            val rotatedRefreshToken = json.optString("refresh_token").ifBlank { refreshToken }
            val claims = CodexCompatibilityProfile.decodeJwtClaims(accessToken)
            val expiresAt = json.optLong("expires_in").takeIf { it > 0L }
                ?.let { System.currentTimeMillis() + it * 1000L }
                ?: claims?.expiresAtEpochSeconds?.times(1000L)
                ?: previous?.expiresAtEpochMillis
            return CodexCredentials(
                accessToken = accessToken,
                refreshToken = rotatedRefreshToken,
                expiresAtEpochMillis = expiresAt,
                accountId = claims?.accountId ?: previous?.accountId,
                residency = claims?.residency ?: previous?.residency,
            ).let(store::writeAndReadBackLocked)
        }
    }

    private fun pollDeviceCode(deviceCode: CodexDeviceCode): DeviceAuthorization? {
        val body = CodexDeviceFlowProtocol.pollRequestPayload(
            deviceAuthId = deviceCode.deviceAuthId,
            userCode = deviceCode.userCode,
        )
        val request = Request.Builder()
            .url(CodexCompatibilityProfile.DEVICE_TOKEN_URL)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", CodexCompatibilityProfile.USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeOAuthRequest(request, CodexOAuthStage.DEVICE_POLL).use { response ->
            val text = CodexOAuthResponseBody.read(response, CodexOAuthStage.DEVICE_POLL)
            if (response.code == 403 || response.code == 404) return null
            if (response.code == 429) {
                throw CodexRateLimitedException(
                    message = "OpenAI 设备登录轮询受到限流，请稍后重试",
                    stage = CodexOAuthStage.DEVICE_POLL,
                    httpStatus = response.code,
                )
            }
            if (response.code != 200) {
                throw CodexOAuthHttpException(
                    message = "轮询 OpenAI 设备登录失败（HTTP ${response.code}）",
                    stage = CodexOAuthStage.DEVICE_POLL,
                    errorCode = "device_code_poll_http",
                    httpStatus = response.code,
                )
            }
            val json = parseJsonObject(
                text = text,
                stage = CodexOAuthStage.DEVICE_POLL,
                errorCode = "device_code_poll_invalid_response",
                message = "OpenAI 设备登录轮询响应无法解析",
            )
            val authorizationCode = json.optString("authorization_code")
                .ifBlank { json.optString("authorizationCode") }
            val codeVerifier = json.optString("code_verifier")
                .ifBlank { json.optString("codeVerifier") }
            if (authorizationCode.isBlank() || codeVerifier.isBlank()) {
                throw CodexOAuthHttpException(
                    message = "OpenAI 设备登录轮询响应缺少必要字段",
                    stage = CodexOAuthStage.DEVICE_POLL,
                    errorCode = "device_code_poll_incomplete",
                    httpStatus = response.code,
                )
            }
            return DeviceAuthorization(authorizationCode, codeVerifier)
        }
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): CodexCredentials {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", CodexCompatibilityProfile.REDIRECT_URI)
            .add("client_id", CodexCompatibilityProfile.PUBLIC_CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder()
            .url(CodexCompatibilityProfile.TOKEN_URL)
            .header("Accept", "application/json")
            .header("User-Agent", CodexCompatibilityProfile.USER_AGENT)
            .post(form)
            .build()
        executeOAuthRequest(request, CodexOAuthStage.TOKEN_EXCHANGE).use { response ->
            val text = CodexOAuthResponseBody.read(response, CodexOAuthStage.TOKEN_EXCHANGE)
            if (response.code == 429) {
                throw CodexRateLimitedException(
                    message = "OpenAI 登录交换受到限流，请稍后重试",
                    stage = CodexOAuthStage.TOKEN_EXCHANGE,
                    httpStatus = response.code,
                )
            }
            if (response.code != 200) {
                throw CodexOAuthHttpException(
                    message = "OpenAI 登录交换失败（HTTP ${response.code}）",
                    stage = CodexOAuthStage.TOKEN_EXCHANGE,
                    errorCode = "token_exchange_http",
                    httpStatus = response.code,
                )
            }
            val json = parseJsonObject(
                text = text,
                stage = CodexOAuthStage.TOKEN_EXCHANGE,
                errorCode = "token_exchange_invalid_response",
                message = "OpenAI 登录交换响应无法解析",
            )
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
                .takeIf { it.isNotBlank() }
            if (accessToken.isBlank()) {
                throw CodexOAuthHttpException(
                    message = "OpenAI 登录交换响应缺少访问令牌",
                    stage = CodexOAuthStage.TOKEN_EXCHANGE,
                    errorCode = "token_exchange_missing_access_token",
                    httpStatus = response.code,
                )
            }
            val claims = CodexCompatibilityProfile.decodeJwtClaims(accessToken)
            return CodexCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochMillis = json.optLong("expires_in").takeIf { it > 0L }
                    ?.let { System.currentTimeMillis() + it * 1000L }
                    ?: claims?.expiresAtEpochSeconds?.times(1000L),
                accountId = claims?.accountId,
                residency = claims?.residency,
            )
        }
    }

    private fun store(): CodexCredentialStore =
        appContext?.let(::CodexCredentialStore)
            ?: throw CodexOAuthException(
                message = "OpenAI Codex 本地服务尚未初始化",
                stage = CodexOAuthStage.CREDENTIAL_STORE,
                errorCode = "credential_store_uninitialized",
            )

    private fun executeOAuthRequest(
        request: Request,
        stage: CodexOAuthStage,
    ): okhttp3.Response = try {
        AgentHttpClient.client.newCall(request).execute()
    } catch (failure: IOException) {
        throw CodexOAuthHttpException(
            message = "OpenAI ${stage.userFacingName}网络不可用，请检查网络后重试",
            stage = stage,
            errorCode = "${stage.code}_network_error",
            cause = failure,
        )
    }

    private fun parseJsonObject(
        text: String,
        stage: CodexOAuthStage,
        errorCode: String,
        message: String,
    ): JSONObject = try {
        JSONObject(text)
    } catch (failure: Throwable) {
        throw CodexOAuthHttpException(
            message = message,
            stage = stage,
            errorCode = errorCode,
            cause = failure,
        )
    }

    private fun readStoreCredentials(store: CodexCredentialStore): CodexCredentials? {
        val result = store.readDetailed()
        logCredentialRead(result)
        return result.credentials
    }

    private fun logCredentialRead(result: io.github.mangi.eta.agent.model.CodexCredentialReadResult) {
        if (result.status == io.github.mangi.eta.agent.model.CodexCredentialReadStatus.VALID ||
            result.status == io.github.mangi.eta.agent.model.CodexCredentialReadStatus.ABSENT
        ) {
            return
        }
        AndroidAgentLogger.warn(
            "Codex credential read failed: status=${result.status.name}, " +
                "diagnostic=${result.diagnostic.toSafeLogToken()}",
        )
    }

    private data class DeviceAuthorization(
        val authorizationCode: String,
        val codeVerifier: String,
    )

}

/**
 * Reads an OAuth response without ever buffering an unbounded provider body.
 *
 * A token response can legitimately be larger than a few kilobytes (the
 * access, refresh and identity tokens are opaque strings). Character-based
 * truncation therefore corrupts otherwise valid JSON. Hermes uses a 1 MiB
 * cap; this implementation keeps the same cap while reading at most one byte
 * beyond it so an oversized response is rejected deterministically.
 */
internal object CodexOAuthResponseBody {
    const val MAX_RESPONSE_BYTES = 1_048_576

    fun read(response: okhttp3.Response, stage: CodexOAuthStage): String {
        val source = try {
            response.body.source()
        } catch (failure: IOException) {
            throw CodexOAuthHttpException(
                message = "OpenAI ${stage.userFacingName}响应读取失败，请稍后重试",
                stage = stage,
                errorCode = "${stage.code}_body_read_error",
                httpStatus = response.code,
                cause = failure,
            )
        }
        val buffer = Buffer()
        var totalBytes = 0L
        try {
            while (totalBytes <= MAX_RESPONSE_BYTES) {
                val read = source.read(
                    buffer,
                    MAX_RESPONSE_BYTES.toLong() + 1L - totalBytes,
                )
                if (read == -1L) break
                if (read == 0L) continue
                totalBytes += read
            }
        } catch (failure: IOException) {
            throw CodexOAuthHttpException(
                message = "OpenAI ${stage.userFacingName}响应读取失败，请稍后重试",
                stage = stage,
                errorCode = "${stage.code}_body_read_error",
                httpStatus = response.code,
                cause = failure,
            )
        }
        if (totalBytes > MAX_RESPONSE_BYTES) {
            throw CodexOAuthHttpException(
                message = "OpenAI ${stage.userFacingName}响应过大，请稍后重试",
                stage = stage,
                errorCode = "response_too_large",
                httpStatus = response.code,
            )
        }
        return buffer.readByteArray().toString(Charsets.UTF_8)
    }
}

internal data class CodexDeviceCode(
    val userCode: String,
    val deviceAuthId: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long,
    val verificationUrl: String,
)

/**
 * Resolve an unauthorized retry after re-reading credentials under the
 * cross-process store lock. If another app process already rotated the pair,
 * adopt it rather than consuming its refresh token a second time.
 */
internal fun codexCredentialsAfterUnauthorized(
    latest: CodexCredentials?,
    failedAccessToken: String,
    refresh: (CodexCredentials, String) -> CodexCredentials,
): CodexCredentials {
    val current = latest
        ?: throw CodexReauthenticationRequiredException("OpenAI Codex 需要重新登录")
    val refreshToken = current.refreshToken?.takeIf(String::isNotBlank)
        ?: throw CodexReauthenticationRequiredException("OpenAI Codex 需要重新登录")
    if (current.accessToken.isNotBlank() && current.accessToken != failedAccessToken) {
        return current
    }
    return refresh(current, refreshToken)
}

/** Stable phase identifiers used for diagnostics and UI mapping. */
internal enum class CodexOAuthStage(
    val code: String,
    val userFacingName: String,
) {
    DEVICE_REQUEST("device_code_request", "设备码请求"),
    DEVICE_POLL("device_code_poll", "设备登录"),
    TOKEN_EXCHANGE("token_exchange", "登录交换"),
    TOKEN_REFRESH("token_refresh", "令牌刷新"),
    CREDENTIAL_STORE("credential_store", "本地凭据保存"),
    UNKNOWN("oauth", "登录"),
}

/**
 * Pure protocol helpers kept separate so the Hermes wire contract can be
 * tested without a network or Android context.
 */
internal object CodexDeviceFlowProtocol {
    const val MIN_POLL_INTERVAL_SECONDS = 3L

    fun normalizedPollIntervalSeconds(value: Long): Long =
        value.coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)

    /** Hermes deviceauth/token accepts only these two JSON fields. */
    fun pollRequestPayload(deviceAuthId: String, userCode: String): String =
        JSONObject()
            .put("device_auth_id", deviceAuthId)
            .put("user_code", userCode)
            .toString()
}

internal open class CodexOAuthException(
    message: String,
    val stage: CodexOAuthStage = CodexOAuthStage.UNKNOWN,
    val errorCode: String = "codex_oauth_error",
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class CodexOAuthHttpException(
    message: String,
    stage: CodexOAuthStage = CodexOAuthStage.UNKNOWN,
    errorCode: String = "codex_http_error",
    httpStatus: Int? = null,
    cause: Throwable? = null,
) : CodexOAuthException(message, stage, errorCode, httpStatus, cause)

internal class CodexReauthenticationRequiredException(
    message: String,
    stage: CodexOAuthStage = CodexOAuthStage.TOKEN_REFRESH,
    errorCode: String = "reauthentication_required",
    httpStatus: Int? = null,
    cause: Throwable? = null,
) : CodexOAuthException(message, stage, errorCode, httpStatus, cause)

internal open class CodexRateLimitedException(
    message: String,
    stage: CodexOAuthStage,
    httpStatus: Int? = 429,
    errorCode: String = "rate_limited",
    cause: Throwable? = null,
) : CodexOAuthException(message, stage, errorCode, httpStatus, cause)

internal class CodexRefreshRateLimitedException(
    message: String,
    stage: CodexOAuthStage = CodexOAuthStage.TOKEN_REFRESH,
    httpStatus: Int? = 429,
    cause: Throwable? = null,
) : CodexRateLimitedException(message, stage, httpStatus, "refresh_rate_limited", cause)
