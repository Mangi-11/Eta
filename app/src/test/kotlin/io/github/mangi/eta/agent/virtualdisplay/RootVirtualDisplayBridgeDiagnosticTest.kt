package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RootVirtualDisplayBridgeDiagnosticTest {

    @Test
    fun formatFailureSummaryIncludesStructuredBridgeDetails() {
        val line = VirtualDisplayCommandMain.RESULT_PREFIX +
            """{"ok":false,"code":"DISPLAY_ISOLATION_UNAVAILABLE","reason":"IME_POLICY_MISMATCH","actual_policy":1,"uid":1000}"""
        val summary = RootVirtualDisplayBridge.formatFailureSummary(
            resultLine = line,
            awaited = true,
            resolvedDisplayId = null,
            disconnected = true,
            alive = false,
        )
        assertTrue(summary.contains("code=DISPLAY_ISOLATION_UNAVAILABLE"))
        assertTrue(summary.contains("reason=IME_POLICY_MISMATCH"))
        assertTrue(summary.contains("actualPolicy=1"))
        assertTrue(summary.contains("uid=1000"))
    }

    @Test
    fun formatFailureSummaryFallsBackWhenBridgeExitsWithoutResultLine() {
        val summary = RootVirtualDisplayBridge.formatFailureSummary(
            resultLine = null,
            awaited = false,
            resolvedDisplayId = null,
            disconnected = false,
            alive = true,
        )
        assertTrue(summary.contains("awaited=false"))
        assertTrue(summary.contains("alive=true"))
    }
}
