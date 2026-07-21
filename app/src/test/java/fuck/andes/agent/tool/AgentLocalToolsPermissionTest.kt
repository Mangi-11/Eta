package fuck.andes.agent.tool

import android.content.Context
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsPermissionTest {
    @Test
    fun terminalPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(terminalEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "run_command",
                argumentsJson = "{\"command\":\"id\"}",
            )
        )

        assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun browserPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(browserEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "browser_use",
                argumentsJson = "{\"action\":\"get_page_info\"}",
            )
        )

        assertEquals("BROWSER_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun foregroundToolIsRejectedWhenEntrySurfaceIsNotReady() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ENTRY_SURFACE_NOT_READY",
                    message = "入口窗口尚未确认关闭",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screen\"}",
            ),
        )

        assertEquals("ENTRY_SURFACE_NOT_READY", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun foregroundToolPropagatesAccessibilityGateFailure() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ACCESSIBILITY_ROOT_ENABLE_FAILED",
                    message = "Root 无法启用 Eta 无障碍服务",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "scroll",
                argumentsJson = "{\"direction\":\"down\"}",
            ),
        )
        val json = JSONObject(result.content)

        assertEquals("ACCESSIBILITY_ROOT_ENABLE_FAILED", json.getString("code"))
        assertEquals("Root 无法启用 Eta 无障碍服务", json.getString("message"))
        tools.close()
    }

    @Test
    fun screenshotCoordinatesRequireACurrentScreenshotCoordinateSpace() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screenshot\"}",
            ),
        )

        assertEquals("INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun textInputWithoutAccessibilityDoesNotSendBlindShellKeys() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "input_text",
                argumentsJson = "{\"text\":\"hello\"}",
            ),
        )

        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result.content).getString("code"))
        tools.close()
    }

    private fun tools(
        terminalEnabled: () -> Boolean = { false },
        browserEnabled: () -> Boolean = { false },
        beforeToolExecution: (String) -> ToolExecutionDecision = {
            ToolExecutionDecision.Allow
        },
    ): AgentLocalTools =
        AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            browserRunId = "test-run",
            terminalToolsEnabled = terminalEnabled,
            browserToolsEnabled = browserEnabled,
            beforeToolExecution = beforeToolExecution,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
