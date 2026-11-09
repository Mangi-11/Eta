package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotStatus
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.ObservedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenObservationMetadataTest {
    @Test
    fun `active accessibility service never starts a competing uiautomator connection`() {
        assertFalse(ScreenObservationMetadata.mayUseUiAutomator(true, true, true, false))
        assertFalse(ScreenObservationMetadata.mayUseUiAutomator(true, true, true, true))
        assertTrue(ScreenObservationMetadata.mayUseUiAutomator(true, false, true, false))
        assertFalse(ScreenObservationMetadata.mayUseUiAutomator(true, false, true, true))
        assertFalse(ScreenObservationMetadata.mayUseUiAutomator(true, false, false, false))
        assertFalse(ScreenObservationMetadata.mayUseUiAutomator(false, false, true, false))
    }

    @Test
    fun `empty tree remains distinct from missing window and query failure`() {
        assertEquals("empty", status(NodeSnapshotStatus.EMPTY))
        assertEquals("no_window", status(NodeSnapshotStatus.NO_WINDOW))
        assertEquals("root_unavailable", status(NodeSnapshotStatus.ROOT_UNAVAILABLE))
        assertEquals("query_failed", status(NodeSnapshotStatus.QUERY_FAILED))
        assertEquals("timeout", status(NodeSnapshotStatus.TIMEOUT))
        assertEquals("busy", status(NodeSnapshotStatus.BUSY))
        assertEquals("service_unavailable", status(NodeSnapshotStatus.SERVICE_UNAVAILABLE))
        assertEquals("empty", rootStatus(ShellActionOutcomePolicy.Outcome.SUCCEEDED))
        assertEquals("query_failed", rootStatus(ShellActionOutcomePolicy.Outcome.FAILED))
        assertEquals("timeout", rootStatus(ShellActionOutcomePolicy.Outcome.TIMED_OUT))
    }

    @Test
    fun `window-only query does not claim a requested node tree`() {
        assertEquals(
            "not_requested",
            ScreenObservationMetadata.uiTreeStatus(false, NodeSnapshotStatus.AVAILABLE, null, 0),
        )
    }

    @Test
    fun `visible window package is not presented as a focused activity`() {
        val focus = ScreenObservationMetadata.focus(
            window = ObservedWindow("com.example.visible", 27, 8, focused = false, active = false),
            uiAutomatorPackageName = null,
        )

        assertEquals("com.example.visible", focus.getString("package"))
        assertEquals("accessibility", focus.getString("source"))
        assertEquals("", focus.getString("component"))
        assertEquals(8, focus.getInt("display_id"))
        assertEquals(27, focus.getInt("window_id"))
        assertFalse(focus.getBoolean("focused"))
        assertFalse(focus.getBoolean("active"))
    }

    @Test
    fun `window without readable root does not borrow another observation package`() {
        val focus = ScreenObservationMetadata.focus(
            window = ObservedWindow(null, 27, 8, focused = true, active = true),
            uiAutomatorPackageName = "com.example.other",
        )

        assertEquals("", focus.getString("package"))
        assertEquals("accessibility", focus.getString("source"))
        assertTrue(focus.getBoolean("focused"))
    }

    @Test
    fun `missing window stays unknown and root dump reports its own source`() {
        val unavailable = ScreenObservationMetadata.focus(null, null)
        assertEquals("", unavailable.getString("package"))
        assertEquals("unavailable", unavailable.getString("source"))
        assertTrue(unavailable.isNull("focused"))

        val dumped = ScreenObservationMetadata.focus(null, "com.example.dumped")
        assertEquals("com.example.dumped", dumped.getString("package"))
        assertEquals("uiautomator", dumped.getString("source"))
        assertTrue(dumped.isNull("focused"))
        assertTrue(dumped.isNull("active"))
    }

    private fun status(value: NodeSnapshotStatus): String =
        ScreenObservationMetadata.uiTreeStatus(true, value, null, 0)

    private fun rootStatus(value: ShellActionOutcomePolicy.Outcome): String =
        ScreenObservationMetadata.uiTreeStatus(true, null, value, 0)
}
