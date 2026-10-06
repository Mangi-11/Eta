package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.model.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainScreenFallbackRoutingTest {
    @Test
    fun enabledSwitchAloneCannotOperateThePrimaryScreen() {
        var requests = 0
        var observed = 0
        tools(
            approval = { _, _ -> requests++; MainScreenFallbackDecision.DENIED },
            observe = { observed++ }).use { tools ->
            assertEquals("MAIN_SCREEN_FALLBACK_DENIED", call(tools, "tap").optString("code"))
            call(tools, "tap")
            assertEquals(1, requests)
            assertEquals(0, observed)
            assertTrue(tools.capabilitiesForRun(capabilities()).virtualScreenEnabled)
        }
    }

    @Test
    fun approvalChangesRouteWithoutReplayingCoordinatesAndRequiresNewObservation() {
        val guards = mutableListOf<String>()
        var observed = 0
        var granted = 0
        tools(
            approval = { _, _ -> MainScreenFallbackDecision.ALLOWED },
            observe = { observed++ },
            before = { guards += it; ToolExecutionDecision.Allow },
            granted = { granted++ }).use { tools ->
            assertEquals("UI_DISPLAY_SWITCHED", call(tools, "tap").optString("code"))
            assertEquals(0, observed)
            assertEquals(
                "MAIN_SCREEN_OBSERVATION_REQUIRED",
                call(tools, "tap_element").optString("code")
            )
            assertTrue(call(tools, "observe_screen").optBoolean("ok"))
            assertEquals(1, observed)
            assertEquals(listOf("virtual_screen", "observe_screen"), guards)
            assertFalse(tools.capabilitiesForRun(capabilities()).virtualScreenEnabled)
            assertEquals(1, granted)
        }
    }

    @Test
    fun closingFallbackPermissionAfterApprovalStopsSubsequentPrimaryActions() {
        var settings = enabledSettings()
        tools(
            settings = { settings },
            approval = { _, _ -> MainScreenFallbackDecision.ALLOWED }).use { tools ->
            assertEquals("UI_DISPLAY_SWITCHED", call(tools, "tap").optString("code"))
            settings = settings.copy(virtualScreenFallbackEnabled = false)
            assertEquals(
                "MAIN_SCREEN_FALLBACK_DISABLED",
                call(tools, "observe_screen").optString("code")
            )
        }
    }

    @Test
    fun cancellationDuringApprovalCannotGrantPrimaryAccess() {
        lateinit var local: AgentLocalTools
        local = tools(approval = { _, _ -> local.close(); MainScreenFallbackDecision.ALLOWED })
        local.use { tools ->
            assertEquals("MAIN_SCREEN_FALLBACK_DISABLED", call(tools, "tap").optString("code"))
            assertTrue(tools.capabilitiesForRun(capabilities()).virtualScreenEnabled)
        }
    }

    @Test
    fun staleCoordinatesAndDisabledSwitchDoNotRequestFallback() {
        tools(
            error = "STALE_OBSERVATION",
            approval = { _, _ -> error("no approval request") }).use { tools ->
            assertEquals("STALE_OBSERVATION", call(tools, "tap").optString("code"))
        }
        tools(
            settings = { Settings(virtualScreenEnabled = true) },
            approval = { _, _ -> error("switch is off") }).use { tools ->
            assertEquals("DISPLAY_APP_UNSUPPORTED", call(tools, "tap").optString("code"))
        }
    }

    @Test
    fun fallbackCapabilitiesPublishSystemActionsOnlyWhenApprovalRequestsAreAllowed() {
        assertEquals(
            "VIRTUAL_ACTION_UNSUPPORTED", capabilities().copy(virtualScreenFallbackEnabled = false)
                .unavailableCode("open_system_panel")
        )
        assertEquals(null, capabilities().unavailableCode("open_system_panel"))
        assertEquals(null, capabilities().copy(rootAvailable = false).unavailableCode("launch_app"))
    }

    private fun enabledSettings() =
        Settings(virtualScreenEnabled = true, virtualScreenFallbackEnabled = true)

    private fun capabilities() = AgentToolCapabilities(
        rootAvailable = true,
        virtualScreenEnabled = true, virtualScreenFallbackEnabled = true
    )

    private fun call(tools: AgentLocalTools, name: String): JSONObject = JSONObject(
        tools.execute(AgentModelClient.ToolCall("test", name, "{}")).content
    )

    private fun tools(
        settings: () -> Settings = ::enabledSettings,
        error: String = "DISPLAY_APP_UNSUPPORTED",
        approval: (String, String) -> MainScreenFallbackDecision,
        observe: () -> Unit = {},
        before: (String) -> ToolExecutionDecision = { ToolExecutionDecision.Allow },
        granted: () -> Unit = {},
    ) = AgentLocalTools(
        RuntimeEnvironment.getApplication(),
        NoOpLogger,
        browserRunId = "fallback-test",
        rootAvailable = { true },
        deviceDirectToolsEnabled = { true },
        virtualScreenSettings = settings,
        virtualUiExecutor = { _, _ ->
            AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put("code", error).toString()
            )
        },
        fallbackApproval = approval,
        onMainScreenFallback = granted,
        beforeToolExecution = before,
        screenObservationProvider = {
            observe(); RootShellDeviceController.Observation(
            "{\"ok\":true}",
            null,
            null,
            null
        )
        })

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
