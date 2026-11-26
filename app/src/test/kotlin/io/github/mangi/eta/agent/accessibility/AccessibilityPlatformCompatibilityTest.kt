package io.github.mangi.eta.agent.accessibility

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AccessibilityPlatformCompatibilityTest {
    @Test
    fun android13KeepsNodeObservationAndReportsUnavailableScreenshot() {
        withConnectedService { service ->
            val root = AccessibilityNodeInfo.obtain().apply {
                packageName = "example.app"
                className = "android.widget.Button"
                text = "继续"
                isVisibleToUser = true
                isEnabled = true
                isClickable = true
                setBoundsInScreen(Rect(10, 10, 100, 80))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
            }
            val window = AccessibilityWindowInfo.obtain().also {
                shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                shadowOf(it).setRoot(root)
            }
            shadowOf(service).setWindowsOnDisplay(0, listOf(window))
            val executor = Executors.newSingleThreadExecutor()
            val observation = try {
                executor.submit<RootShellDeviceController.Observation> {
                    RootShellDeviceController(NoOpLogger, rootAvailable = { false })
                        .observe(includeScreenshot = true, includeUiTree = true, maxNodes = 60)
                }.get(3, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
            val json = JSONObject(observation.content)
            assertTrue(json.getBoolean("ok"))
            assertEquals("继续", json.getJSONArray("ui_nodes").getJSONObject(0).getString("text"))
            assertNotNull(observation.elementObservation)
            assertNull(observation.image)
            assertNull(observation.coordinateSpace)
            val screenshot = json.getJSONObject("screenshot")
            assertFalse(screenshot.getBoolean("attached"))
            assertEquals("failed", screenshot.getString("quality"))
            assertEquals("WINDOW_SCREENSHOT_UNSUPPORTED", screenshot.getString("code"))
            assertTrue(service.globalActionResult("HOME").ok)
        }
    }

    @Test
    fun android13ScreenshotUnavailabilityStillReleasesSubmissionCallback() {
        withConnectedService { service ->
            val submissions = AtomicInteger()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val capture = executor.submit<AgentAccessibilityService.ScreenshotCaptureResult> {
                    service.captureScreenshotExcludingOverlays(setOf("entry.app")) {
                        submissions.incrementAndGet()
                    }
                }.get(2, TimeUnit.SECONDS)
                assertEquals(1, submissions.get())
                assertNull(capture.bitmap)
                assertFalse(capture.complete)
                assertFalse(capture.timedOut)
                assertEquals(0, capture.expectedWindows)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun android13ProtectionIgnoresStaleEnabledStateAndDoesNotBroadcast() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("accessibility_protection", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("enabled", true).commit()
        Settings.Global.putInt(context.contentResolver, AccessibilityProtectionProtocol.SETTING_NAME, 1)
        val broadcastsBefore = shadowOf(context).broadcastIntents.size
        assertFalse(AccessibilityProtectionClient.isSupported())
        assertFalse(AccessibilityProtectionClient.isEnabled(context))
        var result: AccessibilityProtectionClient.ControlResult? = null
        AccessibilityProtectionClient.setEnabled(context, true) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AccessibilityProtectionClient.ControlStatus.UNAVAILABLE, result?.status)
        assertEquals(false, result?.enabled)
        assertEquals(broadcastsBefore, shadowOf(context).broadcastIntents.size)
        assertTrue(preferences.getBoolean("enabled", false))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, AccessibilityProtectionProtocol.SETTING_NAME))
    }

    @Test
    @Config(sdk = [34])
    fun android14ProtectionStillUsesStoredState() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(AccessibilityProtectionClient.isSupported())
        Settings.Global.putInt(context.contentResolver, AccessibilityProtectionProtocol.SETTING_NAME, 1)
        assertTrue(AccessibilityProtectionClient.isEnabled(context))
        Settings.Global.putInt(context.contentResolver, AccessibilityProtectionProtocol.SETTING_NAME, 0)
        assertFalse(AccessibilityProtectionClient.isEnabled(context))
    }

    private inline fun withConnectedService(block: (AgentAccessibilityService) -> Unit) {
        val controller = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            block(service)
        } finally {
            controller.destroy()
        }
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
