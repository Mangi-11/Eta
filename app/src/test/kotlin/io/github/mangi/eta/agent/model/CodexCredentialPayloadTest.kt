package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCredentialPayloadTest {
    @Test
    fun responseClaimsUseStoredAccountWhenAccessTokenOmitsIt() {
        val credentials = CodexCredentials(
            accessToken = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":2000000000}".toByteArray()) + ".signature",
            refreshToken = null,
            expiresAtEpochMillis = null,
            accountId = "stored-account",
            residency = "stored-region",
        )

        val claims = CodexCompatibilityProfile.claimsForCredentials(credentials)

        assertEquals("stored-account", claims.accountId)
        assertEquals("stored-region", claims.residency)
        assertTrue(CodexCompatibilityProfile.requestHeaders(claims, "test-session")
            .any { it.name == "ChatGPT-Account-ID" && it.value == "stored-account" })
    }

    @Test
    fun accessOnlyPayloadRoundTripsAndIsUsableBeforeExpiry() {
        val credentials = CodexCredentials(
            accessToken = "fake-access-token-for-test-only",
            refreshToken = null,
            expiresAtEpochMillis = 2_000_000L,
            accountId = "fake-account-id",
            residency = "test-region",
        )

        val restored = CodexCredentialPayload.fromJson(CodexCredentialPayload.toJson(credentials))

        assertEquals(credentials, restored)
        assertNull(restored.refreshToken)
        assertTrue(restored.isUsable(nowEpochMillis = 1_000_000L))
        assertTrue(restored.copy(expiresAtEpochMillis = null).isUsable(nowEpochMillis = 1_000_000L))
    }

    @Test
    fun accessOnlyCredentialsRequireReauthenticationAfterExpiry() {
        val credentials = CodexCredentials(
            accessToken = "fake-access-token-for-test-only",
            refreshToken = null,
            expiresAtEpochMillis = 1_000L,
        )

        assertFalse(credentials.isUsable(nowEpochMillis = 1_001L))
        assertTrue(credentials.refreshToken.isNullOrBlank())
    }
}
