package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenFallbackRequestTest {
    @Test
    fun approvalIsBoundToOneRequestAndCannotOverrideDenial() {
        val request = MainScreenFallbackRequest("run")
        val next = MainScreenFallbackRequest("run")
        assertTrue(request.token != next.token)
        assertTrue(request.resolve(MainScreenFallbackDecision.DENIED))
        assertFalse(request.resolve(MainScreenFallbackDecision.ALLOWED))
        assertEquals(MainScreenFallbackDecision.DENIED, request.await({ false }, { true }))
    }

    @Test
    fun expiredNotificationCannotAuthorizeARequest() {
        val request = MainScreenFallbackRequest("run", timeoutMs = 0)
        assertFalse(request.resolve(MainScreenFallbackDecision.ALLOWED))
        assertEquals(MainScreenFallbackDecision.TIMEOUT, request.await({ false }, { true }))
    }

    @Test
    fun cancellationAndRevocationInvalidatePendingApproval() {
        val cancelled = MainScreenFallbackRequest("cancelled")
        assertEquals(MainScreenFallbackDecision.CANCELLED, cancelled.await({ true }, { true }))
        assertFalse(cancelled.resolve(MainScreenFallbackDecision.ALLOWED))
        val revoked = MainScreenFallbackRequest("revoked")
        assertEquals(MainScreenFallbackDecision.DISABLED, revoked.await({ false }, { false }))
        assertFalse(revoked.resolve(MainScreenFallbackDecision.ALLOWED))
    }

    @Test
    fun permissionIsRecheckedEvenAfterAllowWasClicked() {
        val request = MainScreenFallbackRequest("run")
        request.resolve(MainScreenFallbackDecision.ALLOWED)
        assertEquals(MainScreenFallbackDecision.DISABLED, request.await({ false }, { false }))
    }
}
