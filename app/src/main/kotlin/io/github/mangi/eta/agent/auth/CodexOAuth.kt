package io.github.mangi.eta.agent.auth

import com.nimbusds.jwt.SignedJWT

/** Codex 授权和请求共享同一客户端与工作区上下文，不能复用其他授权通道的令牌。 */
internal object CodexOAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val CALLBACK_PORT = 1455
    const val ORIGINATOR = "eta"
    const val SCOPE = "openid profile email offline_access"

    fun accountId(accessToken: String): String = try {
        require(accessToken.length in 1..65_536)
        val context = SignedJWT.parse(accessToken).jwtClaimsSet.getJSONObjectClaim("https://api.openai.com/auth")
        (context?.get("chatgpt_account_id") as? String)
            ?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        throw ProviderAuthException("codex_account_missing", "Codex 凭据缺少工作区信息，请重新登录")
    }
}
