package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootShellTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionExecKeepsShellEnvironment() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.terminalAction(
                    action = "open",
                    command = "",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val sessionId = open.getString("session_id")

            val export = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "export ANDES_TEST_VALUE=streaming",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = sessionId,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val echo = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "printf %s \"\$ANDES_TEST_VALUE\"",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = sessionId,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )

            assertTrue(export.toString(), export.getBoolean("ok"))
            assertTrue(echo.toString(), echo.getBoolean("ok"))
            assertEquals("streaming", echo.getString("stdout"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun asyncExecRejectsSessionIdBecauseItDoesNotReuseSessionState() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.terminalAction(
                    action = "open",
                    command = "",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val result = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "sleep 1",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = open.getString("session_id"),
                    jobId = null,
                    async = true,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )

            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("ASYNC_SESSION_UNSUPPORTED", result.getString("code"))
        } finally {
            controller.closeAll()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
