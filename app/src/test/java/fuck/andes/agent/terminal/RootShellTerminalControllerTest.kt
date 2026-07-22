package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger
import java.io.File
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

    @Test
    fun terminalLogsDoNotContainCommandOrWorkingDirectory() {
        val logger = RecordingLogger()
        val controller = RootShellTerminalController(logger)
        val cwd = temporaryFolder.newFolder("sensitive_cwd_marker").absolutePath
        val command = "printf sensitive_command_marker"

        try {
            val result = JSONObject(
                controller.terminalOpenAndExec(
                    command = command,
                    cwd = cwd,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false
                )
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            val logs = logger.messages.joinToString("\n")
            assertTrue(logs, logs.contains("action=open_and_exec"))
            assertTrue(logs, logs.contains("commandChars=${command.length}"))
            assertFalse(logs, logs.contains("sensitive_command_marker"))
            assertFalse(logs, logs.contains("sensitive_cwd_marker"))
            assertFalse(logs, logs.contains(cwd))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun linuxEnvironmentRequiresInstallationAndRootIdentity() {
        val missingController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = File(temporaryFolder.root, "missing-rootfs").absolutePath,
        )
        try {
            val missing = JSONObject(
                missingController.terminalAction(
                    action = "open_and_exec",
                    command = "python3 --version",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "root",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false,
                    environment = "linux",
                ),
            )
            assertFalse(missing.toString(), missing.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", missing.getString("code"))
        } finally {
            missingController.closeAll()
        }

        val rootfs = temporaryFolder.newFolder("ready-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeText("busybox")
        File(rootfs, AlpineEnvironmentPaths.READY_MARKER).writeText("version=3.24.1\n")
        val readyController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = rootfs.absolutePath,
        )
        try {
            val user = JSONObject(
                readyController.terminalAction(
                    action = "open_and_exec",
                    command = "id",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false,
                    environment = "linux",
                ),
            )
            assertFalse(user.toString(), user.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_REQUIRES_ROOT", user.getString("code"))
        } finally {
            readyController.closeAll()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private class RecordingLogger : AgentLogger {
        val messages = mutableListOf<String>()

        override fun debug(message: () -> String) {
            messages += message()
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String, throwable: Throwable?) {
            messages += message
        }
    }
}
