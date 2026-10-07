package io.github.mangi.eta.agent.display

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
class VirtualScreenRoutingTest {
    @Test
    fun unavailableVirtualTreeRemovesNodeToolsButKeepsRootTextAndCoordinates() {
        val capabilities = AgentToolCapabilities(rootAvailable = true, accessibilityAvailable = false,
            virtualScreenEnabled = true, virtualUiTreeAvailable = false)
        for (name in VirtualScreenRoutingPolicy.nodeTools) assertEquals("VIRTUAL_UI_TREE_UNAVAILABLE", capabilities.unavailableCode(name))
        for (name in VirtualScreenRoutingPolicy.coordinateTools) assertEquals(name, null, capabilities.unavailableCode(name))
        assertEquals("ACCESSIBILITY_UNAVAILABLE", capabilities.copy(virtualScreenEnabled = false).unavailableCode("input_text"))
    }
    @Test
    fun allUiToolsUseVirtualExecutionWithoutPrimaryEntryDismissal() {
        val executed = mutableListOf<String>()
        val guards = mutableListOf<String>()
        tools(
            settings = { Settings(virtualScreenEnabled = true) },
            before = { guards += it; ToolExecutionDecision.Allow },
            execute = { name, _ -> executed += name; result(true) }).use { tools ->
            VirtualScreenRoutingPolicy.uiTools.forEach { name ->
                val response = tools.execute(AgentModelClient.ToolCall(name, name, "{}"))
                assertTrue(name, JSONObject(response.content).getBoolean("ok"))
            }
        }
        assertEquals(VirtualScreenRoutingPolicy.uiTools, executed.toSet())
        assertTrue(guards.all { it == "virtual_screen" })
    }

    @Test
    fun revokedVirtualPermissionDoesNotReuseCoordinatesOnPrimaryScreen() {
        var enabled = true
        var calls = 0
        tools(
            settings = { Settings(virtualScreenEnabled = enabled) },
            execute = { _, _ -> calls++; result(true) }).use { tools ->
            assertTrue(
                JSONObject(
                    tools.execute(
                        AgentModelClient.ToolCall(
                            "first",
                            "tap",
                            "{}"
                        )
                    ).content
                ).getBoolean("ok")
            )
            enabled = false
            val rejected = tools.execute(AgentModelClient.ToolCall("second", "tap", "{}"))
            assertEquals("VIRTUAL_SCREEN_DISABLED", JSONObject(rejected.content).getString("code"))
            assertEquals(1, calls)
        }
    }

    @Test
    fun rootAndDirectToolPermissionAreCheckedBeforeVirtualBackend() {
        var root = false
        var direct = true
        tools(
            settings = { Settings(virtualScreenEnabled = true) },
            root = { root },
            direct = { direct },
            execute = { _, _ -> error("must not dispatch") }).use { tools ->
            val call = AgentModelClient.ToolCall("call", "tap", "{}")
            assertEquals("ROOT_REQUIRED", JSONObject(tools.execute(call).content).getString("code"))
            root = true; direct = false
            assertEquals(
                "DEVICE_DIRECT_TOOLS_DISABLED",
                JSONObject(tools.execute(call).content).getString("code")
            )
        }
    }

    @Test
    fun failedVirtualActionIsReturnedWithoutFallbackOrObservationOfPrimaryScreen() {
        var observedPrimary = false
        AgentLocalTools(
            RuntimeEnvironment.getApplication(),
            NoOpLogger,
            browserRunId = "virtual-test",
            deviceDirectToolsEnabled = { true },
            rootAvailable = { true },
            virtualScreenSettings = { Settings(virtualScreenEnabled = true) },
            screenObservationProvider = { observedPrimary = true; error("primary capture") },
            virtualUiExecutor = { _, _ -> AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"DISPLAY_GONE\"}") }).use { tools ->
            val result = tools.execute(AgentModelClient.ToolCall("observe", "observe_screen", "{}"))
            assertEquals("DISPLAY_GONE", JSONObject(result.content).getString("code"))
        }
        assertFalse(observedPrimary)
    }

    @Test
    fun disablingBeforeAnyVirtualOperationPreservesPrimaryModeForNewRuns() {
        val policy = VirtualScreenRoutingPolicy()
        assertFalse(policy.shouldRoute("tap", false))
        assertFalse(policy.shouldRoute("memory_get", true))
        assertFalse(policy.shouldRoute("tap", false))
        assertTrue(policy.shouldRoute("tap", true))
        assertTrue(policy.shouldRoute("observe_screen", false))
        assertFalse(policy.shouldRoute("fetch_url", true))
    }

    @Test
    fun virtualCapabilitiesKeepRootCoordinatesAndSuppressGlobalPanels() {
        val capabilities = AgentToolCapabilities(
            rootAvailable = true, accessibilityAvailable = false,
            virtualScreenEnabled = true
        )
        assertEquals(null, capabilities.unavailableCode("tap"))
        assertEquals(null, capabilities.unavailableCode("observe_screen"))
        assertEquals(null, capabilities.unavailableCode("replace_text"))
        assertEquals(
            "VIRTUAL_ACTION_UNSUPPORTED",
            capabilities.unavailableCode("open_system_panel")
        )
        assertEquals(
            "ROOT_REQUIRED",
            capabilities.copy(rootAvailable = false).unavailableCode("tap")
        )
    }

    private fun tools(
        settings: () -> Settings,
        root: () -> Boolean = { true },
        direct: () -> Boolean = { true },
        before: (String) -> ToolExecutionDecision = { ToolExecutionDecision.Allow },
        execute: (String, JSONObject) -> AgentModelClient.ToolResult,
    ) = AgentLocalTools(
        RuntimeEnvironment.getApplication(), NoOpLogger, browserRunId = "virtual-test",
        deviceDirectToolsEnabled = direct, rootAvailable = root, virtualScreenSettings = settings,
        beforeToolExecution = before, virtualUiExecutor = execute
    )

    private fun result(ok: Boolean) =
        AgentModelClient.ToolResult(JSONObject().put("ok", ok).put("display_id", 3).toString())

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
