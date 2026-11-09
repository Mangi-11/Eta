package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotObservation
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotStatus
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.ObservedWindow
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchVerificationTest {
    @Test
    fun targetWindowConfirmsLaunchWithoutStealingInputFocus() {
        val result = AppLaunchVerification.awaitWindow(
            packageName = TARGET_PACKAGE,
            displayId = TARGET_DISPLAY,
            query = { observation(window = window(focused = false)) },
            checkCancellation = {},
            elapsedRealtime = { 0L },
            sleep = { error("目标窗口已出现，不应继续等待") },
        )

        assertTrue(result.confirmed)
        assertEquals("window_observed", result.launchStatus)
        assertFalse(result.toJson().getBoolean("focused"))
    }

    @Test
    fun waitsThroughMissingWindowWithinOneSharedDeadline() {
        var now = 0L
        val budgets = mutableListOf<Long>()
        val result = AppLaunchVerification.awaitWindow(
            packageName = TARGET_PACKAGE,
            displayId = TARGET_DISPLAY,
            query = { remaining ->
                budgets += remaining
                if (now < 240L) observation(NodeSnapshotStatus.NO_WINDOW)
                else observation(window = window())
            },
            checkCancellation = {},
            elapsedRealtime = { now },
            sleep = { now += it },
        )

        assertTrue(result.confirmed)
        assertEquals(240L, now)
        assertTrue(budgets.zipWithNext().all { (earlier, later) -> later < earlier })
    }

    @Test
    fun samePackageOnAnotherDisplayDoesNotConfirmLaunch() {
        var now = 0L
        val result = AppLaunchVerification.awaitWindow(
            packageName = TARGET_PACKAGE,
            displayId = TARGET_DISPLAY,
            query = { observation(window = window(displayId = 0)) },
            checkCancellation = {},
            elapsedRealtime = { now },
            sleep = { now += it },
        )

        assertFalse(result.confirmed)
        assertEquals("unconfirmed", result.launchStatus)
        assertEquals(1_500L, now)
    }

    @Test
    fun queryFailuresDoNotRetryOrClaimTheAppCannotLaunch() {
        listOf(
            NodeSnapshotStatus.BUSY,
            NodeSnapshotStatus.TIMEOUT,
            NodeSnapshotStatus.SERVICE_UNAVAILABLE,
            NodeSnapshotStatus.QUERY_FAILED,
        ).forEach { status ->
            var queries = 0
            val result = AppLaunchVerification.awaitWindow(
                packageName = TARGET_PACKAGE,
                displayId = TARGET_DISPLAY,
                query = {
                    queries++
                    observation(status)
                },
                checkCancellation = {},
                elapsedRealtime = { 0L },
                sleep = { error("查询通道不可用时不应反复查询") },
            )

            assertEquals(1, queries)
            assertEquals(status, result.status)
            assertEquals("unconfirmed", result.launchStatus)
        }
    }

    @Test
    fun cancellationAfterQueryDiscardsTheObservedWindow() {
        var cancelled = false
        assertThrows(AgentRunCancelledException::class.java) {
            AppLaunchVerification.awaitWindow(
                packageName = TARGET_PACKAGE,
                displayId = TARGET_DISPLAY,
                query = {
                    cancelled = true
                    observation(window = window())
                },
                checkCancellation = { if (cancelled) throw AgentRunCancelledException() },
                elapsedRealtime = { 0L },
                sleep = { error("取消后不应继续等待") },
            )
        }
    }

    private fun observation(
        status: NodeSnapshotStatus = NodeSnapshotStatus.AVAILABLE,
        window: ObservedWindow? = null,
    ) = NodeSnapshotObservation(status = status, snapshot = null, window = window)

    private fun window(
        displayId: Int = TARGET_DISPLAY,
        focused: Boolean = true,
    ) = ObservedWindow(
        packageName = TARGET_PACKAGE,
        windowId = 7,
        displayId = displayId,
        focused = focused,
        active = false,
    )

    private companion object {
        const val TARGET_PACKAGE = "example.app"
        const val TARGET_DISPLAY = 11
    }
}
