package io.github.mangi.eta.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OidcTokenVerifierTest {
    private val now = 1_700_000_000_000L
    private val key = RSAKeyGenerator(2048).keyID("test-key").generate()

    @Test fun validatesSignedIdentityAndRejectsWrongAudienceNonceIssuerAndExpiredTokens() {
        val valid = token()
        assertEquals("account", verify(valid).subject)
        for (invalid in listOf(token(audience = "other-client"), token(nonce = "other-attempt"),
            token(issuer = "https://example.invalid"), token(expiresAt = now - 60_000))) {
            assertThrows(ProviderAuthException::class.java) { verify(invalid) }
        }
    }

    @Test fun rejectsUntrustedSignatureAndAlgorithmEvenWithMatchingKeyId() {
        val other = RSAKeyGenerator(2048).keyID("test-key").generate()
        assertThrows(ProviderAuthException::class.java) {
            OidcTokenVerifier.verifyWithKeys(token(), OAuthProvider.OPENAI, "client", "attempt", JWKSet(other.toPublicJWK()), now)
        }
        val ec = ECKeyGenerator(Curve.P_256).keyID("test-key").generate()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ec.keyID).build(), claims())
        jwt.sign(ECDSASigner(ec))
        assertThrows(ProviderAuthException::class.java) {
            OidcTokenVerifier.verifyWithKeys(jwt.serialize(), OAuthProvider.OPENAI, "client", "attempt", JWKSet(ec.toPublicJWK()), now)
        }
    }

    @Test fun validatesXaiDeviceIdentityWithoutInventingANonceRequirement() {
        val ec = ECKeyGenerator(Curve.P_256).keyID("device-key").generate()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ec.keyID).build(), claims(issuer = OAuthProvider.XAI.issuer))
        jwt.sign(ECDSASigner(ec))
        assertEquals("account", OidcTokenVerifier.verifyWithKeys(jwt.serialize(), OAuthProvider.XAI,
            "client", null, JWKSet(ec.toPublicJWK()), now).subject)
    }

    private fun verify(value: String) = OidcTokenVerifier.verifyWithKeys(value, OAuthProvider.OPENAI,
        "client", "attempt", JWKSet(key.toPublicJWK()), now)

    private fun token(audience: String = "client", nonce: String = "attempt", issuer: String = OAuthProvider.OPENAI.issuer,
        expiresAt: Long = now + 60_000): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims(audience, nonce, issuer, expiresAt),
    ).apply { sign(RSASSASigner(key)) }.serialize()

    private fun claims(audience: String = "client", nonce: String = "attempt", issuer: String = OAuthProvider.OPENAI.issuer,
        expiresAt: Long = now + 60_000) = JWTClaimsSet.Builder().subject("account").issuer(issuer).audience(audience)
        .issueTime(Date(now - 10_000)).expirationTime(Date(expiresAt)).claim("nonce", nonce).build()
}
