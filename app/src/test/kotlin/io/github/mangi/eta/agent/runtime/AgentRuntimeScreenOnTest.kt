package io.github.mangi.eta.agent.runtime

import android.app.Application
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeScreenOnTest {
    @Test
    fun pauseResumeAndCancellationUpdateBothAppAndGuiWindowState() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("active-run")
            val capsule = ComposeView(service)
            service.setField("activeSession", session)
            service.setField("capsuleView", capsule)

            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            service.invoke("requestPause")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            session.controller.cancel()
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun completedRunStopsKeepingScreenOnBeforeServiceDestruction() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("completed-run")
            service.setField("activeSession", session)
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            session.complete(AgentRuntimeWire.RunResult(session.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun destroyingServiceClearsActiveRunScreenRequest() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.setField("activeSession", AgentRuntimeSession("destroyed-run"))
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
        assertFalse(AgentRuntimeService.keepScreenOn.value)
    }

    private fun AgentRuntimeService.setField(name: String, value: Any) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(this, value)
    }

    private fun AgentRuntimeService.invoke(name: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(this)
    }
}
