package io.github.mangi.eta

import io.github.mangi.eta.agent.model.CodexCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EtaCodexOAuthRefreshTest {
    @Test
    fun adoptsRotatedCredentialsFromAnotherProcessWithoutRefreshingAgain() {
        val latest = CodexCredentials(
            accessToken = "fake-new-access-token",
            refreshToken = "fake-rotated-refresh-token",
            expiresAtEpochMillis = null,
        )
        var refreshCalls = 0

        val result = codexCredentialsAfterUnauthorized(
            latest = latest,
            failedAccessToken = "fake-old-access-token",
        ) { _, _ ->
            refreshCalls += 1
            error("A second refresh must not consume the rotated token")
        }

        assertEquals(latest, result)
        assertEquals(0, refreshCalls)
        assertFalse(result.accessToken.contains("fake-old-access-token"))
    }

    @Test
    fun refreshesOnlyWhenThePersistedAccessTokenIsStillTheFailedOne() {
        val latest = CodexCredentials(
            accessToken = "fake-failed-access-token",
            refreshToken = "fake-refresh-token",
            expiresAtEpochMillis = null,
        )
        var refreshCalls = 0

        val result = codexCredentialsAfterUnauthorized(
            latest = latest,
            failedAccessToken = "fake-failed-access-token",
        ) { current, refreshToken ->
            refreshCalls += 1
            assertEquals(latest, current)
            assertEquals("fake-refresh-token", refreshToken)
            CodexCredentials(
                accessToken = "fake-refreshed-access-token",
                refreshToken = "fake-next-refresh-token",
                expiresAtEpochMillis = null,
            )
        }

        assertEquals("fake-refreshed-access-token", result.accessToken)
        assertEquals(1, refreshCalls)
    }
}
