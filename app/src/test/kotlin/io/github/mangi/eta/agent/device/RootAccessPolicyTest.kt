package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessPolicyTest {
    @Test fun firstDetectionRequestsAndExistingAuthorizationIsRechecked() {
        assertTrue(shouldRequestRoot(explicit = false, attempted = false, wasGranted = false))
        assertTrue(shouldRequestRoot(explicit = false, attempted = true, wasGranted = true))
    }

    @Test fun denialStaysQuietUntilUserRequestsAgain() {
        assertFalse(shouldRequestRoot(explicit = false, attempted = true, wasGranted = false))
        assertTrue(shouldRequestRoot(explicit = true, attempted = true, wasGranted = false))
    }

    @Test fun availabilityDoesNotGrantRootWhileWaiting() {
        assertFalse(RootAccessState(suPresent = true, isChecking = true).isGranted)
        assertFalse(RootAccessState(RootAccessStatus.TIMED_OUT, suPresent = true).isGranted)
        assertTrue(RootAccessState(RootAccessStatus.GRANTED, suPresent = true).isGranted)
    }

    @Test fun transientFailurePreservesPreviousAuthorizationWhileExplicitDenialClearsIt() {
        val unavailable = BoundedRootCommandExecutor.Result.failed("ROOT_UNAVAILABLE")
        val timedOut = BoundedRootCommandExecutor.Result(
            exitCode = -2,
            stdout = "",
            stderr = "",
            timedOut = true,
            truncated = false,
        )
        val denied = BoundedRootCommandExecutor.Result(
            exitCode = 1,
            stdout = "",
            stderr = "Permission denied",
            timedOut = false,
            truncated = false,
        )
        val granted = BoundedRootCommandExecutor.Result(
            exitCode = 0,
            stdout = "0\n",
            stderr = "",
            timedOut = false,
            truncated = false,
        )

        assertEquals(RootAccessStatus.UNAVAILABLE to true, classifyRootProbeOutcome(wasGranted = true, unavailable))
        assertEquals(RootAccessStatus.TIMED_OUT to true, classifyRootProbeOutcome(wasGranted = true, timedOut))
        assertEquals(RootAccessStatus.DENIED to false, classifyRootProbeOutcome(wasGranted = true, denied))
        assertEquals(RootAccessStatus.GRANTED to true, classifyRootProbeOutcome(wasGranted = false, granted))
    }

    @Test fun autoRetryRunsOnlyForPreviouslyGrantedNonDeniedStatesAfterCooldown() {
        assertTrue(shouldAutoRetryOnUse(RootAccessStatus.UNAVAILABLE, wasGranted = true, elapsedSinceLastRetryMs = 5_000L))
        assertTrue(shouldAutoRetryOnUse(RootAccessStatus.TIMED_OUT, wasGranted = true, elapsedSinceLastRetryMs = 6_000L))
        assertTrue(shouldAutoRetryOnUse(RootAccessStatus.UNKNOWN, wasGranted = true, elapsedSinceLastRetryMs = 5_000L))
        assertFalse(shouldAutoRetryOnUse(RootAccessStatus.UNAVAILABLE, wasGranted = true, elapsedSinceLastRetryMs = 4_999L))
        assertFalse(shouldAutoRetryOnUse(RootAccessStatus.DENIED, wasGranted = true, elapsedSinceLastRetryMs = 10_000L))
        assertFalse(shouldAutoRetryOnUse(RootAccessStatus.GRANTED, wasGranted = true, elapsedSinceLastRetryMs = 10_000L))
        assertFalse(shouldAutoRetryOnUse(RootAccessStatus.UNAVAILABLE, wasGranted = false, elapsedSinceLastRetryMs = 10_000L))
    }
}
