package io.github.mangi.eta.agent.auth

import okhttp3.HttpUrl.Companion.toHttpUrl

internal object ChatGptPlanOAuth {
    const val RESOURCE = "https://api.openai.com/v1"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPE = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    private const val DYNAMIC_CLIENT = "dynamic_agent_client"
    private val issuedClientPattern = Regex("oaiapp_[a-zA-Z0-9_-]{1,256}")

    fun isIssuedClientId(value: String): Boolean = issuedClientPattern.matches(value)

    fun authorizationUrl(
        previous: ProviderCredential?,
        hostId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        challenge: String,
    ): String = "https://auth.openai.com/api/accounts/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", previous?.clientId ?: DYNAMIC_CLIENT)
        .addQueryParameter("ext_agent_host_id", hostId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", SCOPE)
        .addQueryParameter("resource", RESOURCE)
        .addQueryParameter("state", state)
        .addQueryParameter("nonce", nonce)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("code_challenge", challenge)
        .apply {
            if (previous == null) addQueryParameter("agent_name_hint", "Eta")
            if (previous != null && previous.accessToken.isNotBlank() && !previous.planEnabled) addQueryParameter("prompt", "consent")
            previous?.email?.let { addQueryParameter("login_hint", it) }
            previous?.idToken?.takeIf(String::isNotBlank)?.let { addQueryParameter("id_token_hint", it) }
        }
        .build().toString()
}
