package io.github.mangi.eta.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date

internal object CodexOAuthFixture {
    private val key = RSAKeyGenerator(2048).keyID("fixture-key").generate()
    val jwks: String get() = JWKSet(key.toPublicJWK()).toString()

    fun accessToken(accountId: String = "workspace"): String = sign(JWTClaimsSet.Builder()
        .claim("https://api.openai.com/auth", mapOf("chatgpt_account_id" to accountId)).build())

    fun idToken(nonce: String): String = sign(JWTClaimsSet.Builder()
        .issuer(OAuthProvider.OPENAI.issuer).audience(CodexOAuth.CLIENT_ID).subject("account")
        .issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 3_600_000))
        .claim("nonce", nonce).claim("email", "fixture@example.invalid").build())

    private fun sign(claims: JWTClaimsSet): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims,
    ).apply { sign(RSASSASigner(key)) }.serialize()
}
