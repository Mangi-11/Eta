package io.github.mangi.eta.agent.tool

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementSource
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentAfterActionSummaryTest {
    @Test
    fun unchangedScreenIsReportedSoTheModelDoesNotRepeatTheSameAction() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val summary = AgentAfterActionSummary.build(before, observation("o2", "app.a", listOf(node(0, "搜索"))))
        assertFalse(summary.getBoolean("screen_changed"))
        assertFalse(summary.getBoolean("package_changed"))
        assertEquals("o2", summary.getString("observation_id"))
    }

    @Test
    fun changedScreenCarriesCompactNodesAndPackageSwitch() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val after = observation("o2", "app.b", listOf(node(0, "结果", clickable = true)))
        val summary = AgentAfterActionSummary.build(before, after)
        assertTrue(summary.getBoolean("screen_changed"))
        assertTrue(summary.getBoolean("package_changed"))
        val compact = summary.getJSONArray("ui_nodes").getJSONObject(0)
        assertEquals("结果", compact.getString("text"))
        assertEquals("TextView", compact.getString("class"))
        assertTrue(compact.getBoolean("clickable"))
        // 默认值为 false 的字段不输出，节省 token。
        assertFalse(compact.has("editable"))
        assertFalse(compact.has("password"))
    }

    @Test
    fun toggleStateAndHintAreVisibleToTheModel() {
        val toggle = node(0, "蓝牙").copy(checked = false)
        val field = node(1, "").copy(editable = true, hint = "搜索联系人")
        val nodes = AgentAfterActionSummary.build(null, observation("o1", "app", listOf(toggle, field)))
            .getJSONArray("ui_nodes")
        assertFalse(nodes.getJSONObject(0).getBoolean("checked"))
        assertEquals("搜索联系人", nodes.getJSONObject(1).getString("hint"))
        assertFalse(nodes.getJSONObject(1).has("checked"))
    }

    private fun observation(id: String, pkg: String, nodes: List<UiNode>) = ElementObservation(
        id = id, source = ElementSource.ACCESSIBILITY, packageName = pkg, windowId = 1,
        nodes = nodes, maxNodes = 30, truncated = false,
    )

    private fun node(index: Int, text: String, clickable: Boolean = false) = UiNode(
        index = index, text = text, desc = "", className = "android.widget.TextView", packageName = "app",
        viewId = "", bounds = Rect(0, 0, 100, 50), clickable = clickable, longClickable = false,
        scrollable = false, focused = false, editable = false, password = false, enabled = true,
    )
}
