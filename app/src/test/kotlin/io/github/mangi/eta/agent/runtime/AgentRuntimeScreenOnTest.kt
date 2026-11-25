package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeScreenOnTest {
    @Test
    fun pauseResumeAndCancellationUpdateWindowAndWakeLockState() {
        ShadowPowerManager.reset()
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = service.registerRun("active-run").session
            val capsule = ComposeView(service)
            service.overlayHost.capsuleView = capsule

            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLock.isHeld)

            service.invokeForRun("requestPause", session.runId)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
            assertFalse(wakeLock.isHeld)

            service.invoke("requestResume")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)
            assertTrue(wakeLock.isHeld)

            session.controller.cancel()
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
            assertFalse(wakeLock.isHeld)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
            assertFalse(wakeLock.isHeld)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun completedRunStopsKeepingScreenOnBeforeServiceDestruction() {
        ShadowPowerManager.reset()
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = service.registerRun("completed-run").session
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLock.isHeld)

            session.complete(AgentRuntimeWire.RunResult(session.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(wakeLock.isHeld)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(wakeLock.isHeld)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun destroyingServiceClearsActiveRunScreenRequestAndReleasesWakeLock() {
        ShadowPowerManager.reset()
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val wakeLock = try {
            val service = serviceController.get()
            service.registerRun("destroyed-run")
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            ShadowPowerManager.getLatestWakeLock().also { assertTrue(it.isHeld) }
        } finally {
            serviceController.destroy()
        }
        assertFalse(AgentRuntimeService.keepScreenOn.value)
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun anyActiveRunKeepsScreenOnRegardlessOfGuiOwnership() {
        ShadowPowerManager.reset()
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            service.assertExecutionStatus(background.overlayState.status, 1)
            assertNull(guiCoordinator.state.value.ownerRunId)
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLock.isHeld)

            val guiRun = service.registerRun("gui-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.GeneratingAnswer)
            }
            val guiControl = guiCoordinator.openRun(guiRun.runId, guiRun.session.controller)
            guiControl.withAction { }
            service.assertExecutionStatus(AgentOverlayStatus.GeneratingAnswer, 2)
            assertTrue(executionSuppressed())
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(wakeLock.isHeld)

            guiControl.release()
            service.assertExecutionStatus(background.overlayState.status, 2)
            assertFalse(executionSuppressed())
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(wakeLock.isHeld)

            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(wakeLock.isHeld)

            guiRun.session.complete(AgentRuntimeWire.RunResult(guiRun.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(wakeLock.isHeld)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun keepScreenOnSettingToggleControlsActiveRunScreenRequest() {
        ShadowPowerManager.reset()
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        val prefs = checkNotNull(Prefs.localAgentPreferences())
        prefs.edit().putBoolean(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED, false).commit()

        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.registerRun("toggled-run", ownsGui = false)
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertNull(ShadowPowerManager.getLatestWakeLock())

            prefs.edit().putBoolean(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED, true).commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLock.isHeld)

            prefs.edit().putBoolean(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED, false).commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(wakeLock.isHeld)
        } finally {
            prefs.edit().remove(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED).commit()
            serviceController.destroy()
        }
    }

    @Test
    fun cancelledPausedOwnerKeepsTheResumeControlAvailable() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val run = service.registerRun("paused-run")
            service.invokeForRun("requestPause", run.runId)
            run.session.controller.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(service.field<GuiControlCoordinator>("guiControl").state.value.userPaused)
            assertEquals(AgentOverlayPhase.PAUSED, service.overlayHost.overlayState.phase)
            service.invoke("requestResume")
            assertFalse(service.field<GuiControlCoordinator>("guiControl").state.value.userPaused)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun retainedGuiPauseDoesNotPauseBackgroundNotificationOrKeepFinishedRunsPromoted() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val guiRun = service.registerRun("paused-run")
            service.invokeForRun("requestPause", guiRun.runId)
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            service.assertExecutionStatus(AgentOverlayStatus.Paused, 2)

            guiRun.session.controller.cancel()
            guiRun.session.complete(AgentRuntimeWire.RunResult(guiRun.runId, true, "完成"))
            service.assertExecutionStatus(background.overlayState.status, 1)
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            assertTrue(guiCoordinator.state.value.userPaused)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertEquals(AgentOverlayPhase.PAUSED, service.overlayHost.overlayState.phase)
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            service.assertExecutionStatus(null, 0)
            assertTrue(guiCoordinator.state.value.userPaused)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    private fun AgentRuntimeService.assertExecutionStatus(status: AgentOverlayStatus?, activeRunCount: Int) {
        invoke("publishExecutionState")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(status, AgentExecutionService::class.java.getDeclaredField("runStatus").apply {
            isAccessible = true
        }.get(null))
        assertEquals(activeRunCount, AgentExecutionService::class.java.getDeclaredField("activeRunCount").apply {
            isAccessible = true
        }.getInt(null))
    }

    private fun executionSuppressed(): Boolean = AgentExecutionService::class.java.getDeclaredField("suppressLiveUpdate").apply {
        isAccessible = true
    }.getBoolean(null)

    private fun AgentRuntimeService.registerRun(runId: String, ownsGui: Boolean = true): RuntimeRunRegistry.Run {
        val registry = field<RuntimeRunRegistry>("runs")
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(runId, beforeTerminalPublish = { registry.finish(runId, session) })
        val request = AgentRuntimeWire.RunRequest(
            runId, "测试", AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            emptyList(),
        )
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
        run.prepared(request)
        if (ownsGui) field<GuiControlCoordinator>("guiControl").openRun(runId, session.controller).withAction { }
        shadowOf(Looper.getMainLooper()).idle()
        return run
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> AgentRuntimeService.field(name: String): T = AgentRuntimeService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(this) as T

    private fun AgentRuntimeService.invokeForRun(name: String, runId: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name, String::class.java).apply {
            isAccessible = true
        }.invoke(this, runId)
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
