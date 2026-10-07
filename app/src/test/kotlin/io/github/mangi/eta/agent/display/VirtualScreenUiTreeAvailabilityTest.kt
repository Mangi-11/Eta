package io.github.mangi.eta.agent.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenUiTreeAvailabilityTest {
    private var now = 0L
    private val availability = VirtualScreenUiTreeAvailability { now }
    private val info = VirtualDisplayInfo("session", "owner", 7, 1080, 2400,
        focusedPackage = "com.tencent.mm")

    @Test
    fun oneEmptyObservationNeverDisablesNodeTools() {
        availability.record(info, 10, hasNodes = false)
        now = 10_000
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun rapidRetriesCannotCountAsIndependentEmptyObservations() {
        repeat(100) { availability.record(info, 10, hasNodes = false) }
        now = 2000
        availability.record(info, 10, hasNodes = false)
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun threeObservationsNeedTheFullStartupGracePeriod() {
        for (time in listOf(0L, 500L, 1000L)) {
            now = time
            availability.record(info, 10, hasNodes = false)
        }
        assertFalse(availability.unavailableFor(info))
        now = 1500
        availability.record(info, 10, hasNodes = false)
        assertTrue(availability.unavailableFor(info))
    }

    @Test
    fun missingWindowOrServiceStaysPendingEvenAfterRepeatedObservations() {
        repeat(10) {
            now += 1000
            availability.record(info, null, hasNodes = false)
        }
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun nodesReturningToTheSameWindowImmediatelyRestoreTools() {
        confirmEmptyWindow()
        availability.record(info, 10, hasNodes = true)
        assertFalse(availability.unavailableFor(info))
        now += 1000
        availability.record(info, 10, hasNodes = false)
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun aNewAppDoesNotInheritThePreviousAppsRestriction() {
        confirmEmptyWindow()
        val next = info.copy(focusedPackage = "com.tencent.mobileqq")
        assertFalse(availability.unavailableFor(next))
        availability.updateScope(next)
        availability.record(next, 20, hasNodes = false)
        assertFalse(availability.unavailableFor(next))
        availability.record(next, 20, hasNodes = true)
        assertFalse(availability.unavailableFor(next))
    }

    @Test
    fun anotherWindowInTheSameAppStartsWithFreshEvidence() {
        confirmEmptyWindow()
        availability.record(info, 11, hasNodes = false)
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun aNewDisplayOrSessionCannotInheritTheRestriction() {
        confirmEmptyWindow()
        for (next in listOf(info.copy(sessionId = "next"), info.copy(displayId = 8))) {
            assertFalse(availability.unavailableFor(next))
            availability.updateScope(next)
            availability.record(next, 10, hasNodes = false)
            assertFalse(availability.unavailableFor(next))
        }
        assertFalse(availability.unavailableFor(null))
    }

    @Test
    fun manualInputRequiresANewAssessment() {
        confirmEmptyWindow()
        val next = info.copy(manualInputGeneration = 1)
        assertFalse(availability.unavailableFor(next))
        availability.record(next, 10, hasNodes = false)
        assertFalse(availability.unavailableFor(next))
    }

    @Test
    fun disappearingWindowsResetConsecutiveEmptyEvidence() {
        confirmEmptyWindow()
        availability.record(info, null, hasNodes = false)
        assertFalse(availability.unavailableFor(info))
        availability.record(info, 10, hasNodes = false)
        assertFalse(availability.unavailableFor(info))
    }

    @Test
    fun unknownFocusCannotConfirmAnAppLimitation() {
        val unknown = info.copy(focusedPackage = "")
        repeat(10) {
            now += 1000
            availability.record(unknown, 10, hasNodes = false)
        }
        assertFalse(availability.unavailableFor(unknown))
    }

    private fun confirmEmptyWindow() {
        for (time in listOf(0L, 750L, 1500L)) {
            now = time
            availability.record(info, 10, hasNodes = false)
        }
        assertTrue(availability.unavailableFor(info))
    }
}
