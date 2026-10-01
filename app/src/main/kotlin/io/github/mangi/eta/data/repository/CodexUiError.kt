package io.github.mangi.eta.data.repository

import io.github.mangi.eta.CodexOAuthException
import io.github.mangi.eta.CodexOAuthHttpException
import io.github.mangi.eta.CodexOAuthStage
import io.github.mangi.eta.CodexRateLimitedException
import io.github.mangi.eta.CodexReauthenticationRequiredException
import io.github.mangi.eta.agent.model.CodexCredentialStoreException

/**
 * Fixed, actionable copy for OAuth-facing surfaces.
 *
 * Throwable messages and HTTP response bodies are deliberately not returned
 * here: an OAuth response can contain token-shaped data or provider internals.
 */
internal object CodexUiError {
    fun login(error: Throwable): String = when (error) {
        is CodexCredentialStoreException -> "OpenAI Codex 登录未完成，本地凭据保存失败，请检查存储空间后重试"
        is CodexRateLimitedException -> "OpenAI Codex 登录请求受到限流，请稍后重试"
        is CodexReauthenticationRequiredException -> "OpenAI Codex 登录已失效，请重新登录"
        is CodexOAuthHttpException -> loginHttp(error)
        is CodexOAuthException -> "OpenAI Codex 登录失败，请稍后重试"
        else -> "OpenAI Codex 登录失败，请检查网络或稍后重试"
    }

    fun models(error: Throwable): String = when (error) {
        is CodexRateLimitedException -> "OpenAI Codex 模型列表请求受到限流，请稍后重试"
        is CodexReauthenticationRequiredException -> "OpenAI Codex 登录已失效，请重新登录"
        is CodexModelCatalogException -> modelCatalog(error)
        is CodexCredentialStoreException -> "OpenAI Codex 本地凭据不可用，请重新登录"
        is CodexOAuthException -> "OpenAI Codex 模型列表暂时不可用，请稍后重试"
        else -> "OpenAI Codex 模型列表暂时不可用，请稍后重试"
    }

    private fun modelCatalog(error: CodexModelCatalogException): String = when (error.kind) {
        CodexModelCatalogFailureKind.HTTP -> when (error.statusCode) {
            401 -> "OpenAI Codex 模型目录返回 HTTP 401；已保留离线兜底列表。登录与对话可正常时，这不等于账号缺少模型权限"
            403 -> "OpenAI Codex 模型目录未获授权（HTTP 403）；已保留离线兜底列表"
            429 -> "OpenAI Codex 模型列表受到限流（HTTP 429），请稍后重试"
            else -> "OpenAI Codex 模型列表请求失败（HTTP ${error.statusCode ?: "未知"}），请稍后重试"
        }
        CodexModelCatalogFailureKind.EMPTY ->
            "OpenAI Codex 模型列表为空（HTTP 200），请确认此账号有可用模型"
        CodexModelCatalogFailureKind.JWT_MISSING_ACCOUNT_ID ->
            "OpenAI Codex 登录令牌缺少账户标识，请重新登录"
        CodexModelCatalogFailureKind.NETWORK ->
            "无法连接 OpenAI Codex 模型目录；已保留离线兜底列表"
        CodexModelCatalogFailureKind.RESPONSE_TOO_LARGE ->
            "OpenAI Codex 模型目录响应超出安全大小限制；已保留离线兜底列表"
        CodexModelCatalogFailureKind.PARSE ->
            "OpenAI Codex 模型列表响应无法解析，请稍后重试"
    }

    fun configuration(error: Throwable): String = when (error) {
        is CodexCredentialStoreException -> "OpenAI Codex 本地凭据保存失败，请检查存储空间后重试"
        is CodexRateLimitedException -> "OpenAI Codex 请求受到限流，请稍后重试"
        is CodexReauthenticationRequiredException -> "OpenAI Codex 登录已失效，请重新登录"
        is CodexOAuthException -> "OpenAI Codex 配置同步失败，请稍后重试"
        else -> "OpenAI Codex 配置同步失败，请稍后重试"
    }

    private fun loginHttp(error: CodexOAuthHttpException): String = when {
        error.errorCode == "device_code_expired" -> "OpenAI 设备码已过期，请重新获取"
        error.stage == CodexOAuthStage.DEVICE_REQUEST && error.httpStatus in setOf(403, 404) ->
            "OpenAI 设备码功能可能未开启，请稍后重试或使用其他登录方式"
        error.errorCode.endsWith("network_error") ->
            "无法连接 OpenAI 登录服务，请检查网络后重试"
        error.errorCode == "response_too_large" ->
            "OpenAI 登录响应过大，请稍后重试"
        error.errorCode.endsWith("body_read_error") ->
            "OpenAI 登录响应读取失败，请稍后重试"
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_http" ->
            "OpenAI 登录交换失败（HTTP ${error.httpStatus ?: "未知"}），请确认设备登录已完成后重试"
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_invalid_response" ->
            "OpenAI 登录交换响应无效，请稍后重试"
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_missing_access_token" ->
            "OpenAI 登录交换未返回访问令牌，请重新获取设备码"
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE ->
            "OpenAI 登录交换失败，请确认设备登录已完成后重试"
        error.stage == CodexOAuthStage.DEVICE_POLL ->
            "OpenAI 设备登录暂未完成或已失效，请重新获取设备码"
        else -> "OpenAI Codex 登录服务暂时不可用，请稍后重试"
    }
}
