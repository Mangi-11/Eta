package io.github.mangi.eta.agent.device

import android.app.Application
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.assertEquals
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

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
