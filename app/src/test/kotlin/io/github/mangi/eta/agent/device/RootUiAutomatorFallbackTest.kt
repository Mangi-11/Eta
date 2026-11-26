package io.github.mangi.eta.agent.device

import android.app.Application
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RootUiAutomatorFallbackTest {
    @Test
    fun reconnectedAccessibilityStopsRootDumpAtExecutionBoundary() {
        val serviceController = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(serviceController.get(), "onServiceConnected")
            RootShellDeviceController(
                logger = NoOpLogger,
                rootAvailable = { error("无障碍已重连，不应再进入 Root 读取通道") },
            ).use { device ->
                val result = ReflectionHelpers.callInstanceMethod<Any>(
                    device,
                    "readUiNodeDump",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 30),
                )

                assertTrue(ReflectionHelpers.getField<List<*>>(result, "nodes").isEmpty())
                assertEquals(
                    ShellActionOutcomePolicy.Outcome.FAILED,
                    ReflectionHelpers.getField<ShellActionOutcomePolicy.Outcome>(result, "outcome"),
                )
            }
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun connectedAccessibilityWaitForTextNeverInvokesRootDump() {
        val serviceController = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(serviceController.get(), "onServiceConnected")
            RootShellDeviceController(
                logger = NoOpLogger,
                rootAvailable = { error("无障碍在线时 waitForText 不应回退 Root dump") },
            ).use { device ->
                val result = JSONObject(device.waitForText("不存在", 500, true, "contains"))
                assertFalse(result.getBoolean("ok"))
                assertEquals("TIMEOUT", result.getString("code"))
            }
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun virtualDisplayWithoutAccessibilityNeverInvokesRootDump() {
        RootShellDeviceController(
            logger = NoOpLogger,
            rootAvailable = { error("虚拟屏无障碍缺失时不应调用 Root uiautomator") },
        ).use { device ->
            val scrollResult = JSONObject(device.scroll("down", displayId = 3))
            assertFalse(scrollResult.getBoolean("ok"))
            assertEquals("ACCESSIBILITY_UNAVAILABLE", scrollResult.getString("code"))

            val waitResult = JSONObject(device.waitForText("目标", 500, true, "contains", displayId = 3))
            assertFalse(waitResult.getBoolean("ok"))
            assertEquals("ACCESSIBILITY_UNAVAILABLE", waitResult.getString("code"))
        }
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
