package io.github.mangi.eta.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class VerifiedProviderIdentity(val subject: String, val email: String?, val name: String?)

internal class OidcTokenVerifier(private val http: OAuthHttpClient) {
    private class CachedKeys(val keys: JWKSet, val expiresAt: Long)
    private val cache = ConcurrentHashMap<OAuthProvider, CachedKeys>()

    fun verify(
        encoded: String,
        provider: OAuthProvider,
        clientId: String,
        nonce: String?,
        controller: AgentRunController,
    ): VerifiedProviderIdentity {
        val jwt = parse(encoded)
        var keys = cache[provider]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.keys
            ?: fetchKeys(provider, controller)
        if (keys.getKeyByKeyId(jwt.header.keyID) == null) keys = fetchKeys(provider, controller)
        return verifyWithKeys(encoded, provider, clientId, nonce, keys, System.currentTimeMillis())
    }

    private fun fetchKeys(provider: OAuthProvider, controller: AgentRunController): JWKSet {
        val response = http.get(provider.jwksUrl, controller)
        if (!response.successful) throw ProviderAuthException("oidc_keys_unavailable", "暂时无法验证账号身份，请稍后重试")
        val keys = try {
            JWKSet.parse(response.json.toString())
        } catch (_: Exception) {
            throw ProviderAuthException("oidc_invalid_keys", "授权服务的身份验证密钥无效")
        }
        cache[provider] = CachedKeys(keys, System.currentTimeMillis() + 3_600_000)
        return keys
    }

    companion object {
        internal fun verifyWithKeys(
            encoded: String,
            provider: OAuthProvider,
            clientId: String,
            nonce: String?,
            keys: JWKSet,
            now: Long,
        ): VerifiedProviderIdentity = try {
            val jwt = parse(encoded)
            require(jwt.header.algorithm == JWSAlgorithm.parse(provider.signingAlgorithm))
            require(!jwt.header.keyID.isNullOrBlank())
            require(jwt.header.criticalParams.isNullOrEmpty())
            val key = requireNotNull(keys.getKeyByKeyId(jwt.header.keyID))
            require(!key.isPrivate)
            require(key.keyUse == null || key.keyUse == KeyUse.SIGNATURE)
            require(key.keyOperations.isNullOrEmpty() || KeyOperation.VERIFY in key.keyOperations)
            require(key.algorithm == null || key.algorithm == jwt.header.algorithm)
            val verifier = when {
                key is RSAKey && jwt.header.algorithm == JWSAlgorithm.RS256 -> {
                    require(key.modulus.decodeToBigInteger().bitLength() >= 2048)
                    RSASSAVerifier(key.toRSAPublicKey())
                }
                key is ECKey && jwt.header.algorithm == JWSAlgorithm.ES256 -> ECDSAVerifier(key.toECPublicKey())
                else -> throw IllegalArgumentException()
            }
            require(jwt.verify(verifier))
            val claims = jwt.jwtClaimsSet
            require(claims.issuer == provider.issuer)
            require(clientId in claims.audience)
            val authorizedParty = claims.getStringClaim("azp")
            if (claims.audience.size > 1 || authorizedParty != null) require(authorizedParty == clientId)
            require(requireNotNull(claims.expirationTime).time > now - CLOCK_SKEW_MS)
            require(requireNotNull(claims.issueTime).time <= now + CLOCK_SKEW_MS)
            claims.notBeforeTime?.let { require(it.time <= now + CLOCK_SKEW_MS) }
            if (nonce != null) {
                val actual = requireNotNull(claims.getStringClaim("nonce"))
                require(MessageDigest.isEqual(nonce.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8)))
            }
            val subject = requireNotNull(claims.subject).also { require(it.isNotBlank() && it.length <= 512) }
            VerifiedProviderIdentity(
                subject,
                claims.getStringClaim("email")?.takeIf { it.length <= 254 },
                claims.getStringClaim("name")?.takeIf { it.length <= 120 },
            )
        } catch (error: ProviderAuthException) {
            throw error
        } catch (_: Exception) {
            throw ProviderAuthException("oidc_identity_invalid", "无法验证登录账号，请重新登录")
        }

        private fun parse(encoded: String): SignedJWT = try {
            require(encoded.length in 1..65_536)
            SignedJWT.parse(encoded)
        } catch (_: Exception) {
            throw ProviderAuthException("oidc_token_invalid", "授权服务返回了无效的身份凭据")
        }

        private const val CLOCK_SKEW_MS = 30_000L
    }
}
