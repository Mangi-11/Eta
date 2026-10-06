package io.github.mangi.eta.agent.display

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenAccessibilityTest {
    @Test
    fun observingSecondaryDisplayNeverReturnsViewerNodes() = withService { service ->
        shadowOf(service).setRootInActiveWindow(node("eta.viewer", "primary"))
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setDisplayId(3)
            setFocused(true)
            setRoot(node("virtual.app", "secondary"))
        }
        shadowOf(service).setWindowsOnDisplay(3, listOf(window))
        val snapshot = service.captureNodeSnapshot(60, 3)!!
        assertEquals(3, snapshot.displayId)
        assertEquals("virtual.app", snapshot.packageName)
        assertEquals("secondary", snapshot.nodes.single().text)
        assertEquals("virtual.app", service.currentPackageName(3))
        assertEquals("eta.viewer", service.currentPackageName())
    }

    @Test
    fun missingSecondaryWindowReturnsNoNodesInsteadOfPrimaryFallback() = withService { service ->
        shadowOf(service).setRootInActiveWindow(node("eta.viewer", "must not read"))
        assertNull(service.captureNodeSnapshot(60, 9))
        assertEquals(emptyList<AgentAccessibilityService.UiNode>(), service.queryNodes(60, 9))
        assertNull(service.currentPackageName(9))
    }

    @Test
    fun focusedPrimaryEditorCannotReceiveVirtualText() = withService { service ->
        val primary = node("eta.viewer", "user draft").apply { isEditable = true; isFocused = true }
        shadowOf(service).setRootInActiveWindow(primary)
        assertEquals("NO_FOCUSED_EDITABLE", service.inputTextFocused("virtual input", 9).code)
        assertEquals(
            "NO_FOCUSED_EDITABLE",
            service.setTextNode(null, null, "virtual replacement", 9).code
        )
        assertEquals("user draft", primary.text)
    }

    private fun node(packageName: String, text: String) = AccessibilityNodeInfo.obtain().apply {
        this.packageName = packageName
        this.text = text
        className = "android.widget.Button"
        isVisibleToUser = true
        isEnabled = true
        isClickable = true
        setBoundsInScreen(Rect(10, 10, 100, 80))
    }

    private inline fun withService(block: (AgentAccessibilityService) -> Unit) {
        val controller = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            val service = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            block(service)
        } finally {
            controller.destroy()
        }
    }
}
