package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.mangi.eta.agent.terminal.linux.AlpineEnvironmentPaths

class RootShellTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionExecKeepsShellEnvironment() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "open")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("identity", "user")
                )
            )
            val sessionId = open.getString("session_id")

            val export = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "export ETA_TEST_VALUE=streaming")
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("session_id", sessionId)
                )
            )
            val echo = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "printf %s \"\$ETA_TEST_VALUE\"")
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("session_id", sessionId)
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
    fun stoppingBackgroundJobDoesNotCloseTerminalSession() {
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "jobs.json"),
            daemonDir = File(temporaryFolder.root, "logs").path, rootAvailable = { false })
        val jobs = BackgroundTerminalJobs(supervisor, NoopLogger)
        val controller = RootShellTerminalController(NoopLogger, detachedSupervisor = supervisor, backgroundJobs = jobs, rootAvailable = { false })
        try {
            val open = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path)))
            val sessionId = open.getString("session_id")
            val started = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "start").put("command", "sleep 30")
                .put("cwd", temporaryFolder.root.path)))
            assertTrue(started.toString(), started.getBoolean("ok"))
            assertEquals("terminal_job", started.getString("tool"))
            assertEquals("start", started.getString("action"))
            val jobId = started.getString("job_id")

            val stopped = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "stop").put("job_id", jobId)))
            assertEquals("stopped", stopped.getString("status"))
            assertEquals("stop", stopped.getString("action"))
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf session_alive").put("session_id", sessionId)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("session_alive", result.getString("stdout"))
            val removed = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "read").put("job_id", jobId)))
            assertEquals("stopped", removed.getString("status"))
        } finally {
            controller.closeAll()
            jobs.close()
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
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", command)
                    .put("cwd", cwd)
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                )
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            val logs = logger.messages.joinToString("\n")
            assertTrue(logs, logs.contains("action=exec"))
            assertTrue(logs, logs.contains("commandChars=${command.length}"))
            assertFalse(logs, logs.contains("sensitive_command_marker"))
            assertFalse(logs, logs.contains("sensitive_cwd_marker"))
            assertFalse(logs, logs.contains(cwd))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun terminalExecAcceptsExtraFieldsAndUsesExecutionDefaults() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "EXEC").put("command", "printf result")
                .put("identity", "USER").put("environment", "ANDROID")
                .put("cwd", temporaryFolder.root.path).put("timeout_ms", "5000")
                .put("unused", true)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("result", result.getString("stdout"))
            assertEquals("user", result.getString("identity"))
        } finally { controller.closeAll() }
    }

    @Test
    fun sessionExecUsesStoredEnvironmentDespiteRedundantArguments() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val open = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("identity", "user").put("cwd", temporaryFolder.root.path)))
            assertTrue(open.toString(), open.getBoolean("ok"))
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec").put("command", "pwd")
                .put("session_id", open.getString("session_id"))
                .put("cwd", "/missing").put("identity", "root").put("environment", "linux")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(temporaryFolder.root.canonicalPath, result.getString("stdout").trim())
            assertEquals("user", result.getString("identity"))
        } finally { controller.closeAll() }
    }

    @Test
    fun longCommandsCanWriteFiles() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        val content = "a".repeat(20_000)
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec").put("cwd", temporaryFolder.root.path)
                .put("command", "cat > note.txt <<'EOF'\n$content\nEOF")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("$content\n", File(temporaryFolder.root, "note.txt").readText())
        } finally { controller.closeAll() }
    }

    @Test
    fun oneShotReturnsObservedRuntimeWithoutMixingMetadataIntoOutput() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val args = JSONObject().put("action", "exec").put("command", "printf visible_output")
                .put("identity", "user").put("cwd", temporaryFolder.root.absolutePath)
            val result = JSONObject(controller.executeTool("terminal", args))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("visible_output", result.getString("stdout"))
            assertEquals("exited", result.getString("status"))
            assertEquals("exec", result.getString("action"))
            val runtime = result.getJSONObject("runtime")
            assertEquals("user", runtime.getString("host_identity"))
            assertTrue(runtime.toString(), runtime.getInt("uid") >= 0)
            assertFalse(runtime.getString("shell_provider").isBlank())
            assertTrue(runtime.getJSONArray("available_commands").toString().contains("sh"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun outputJustOverDisplayLimitIsMarkedTruncated() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec")
                .put("command", "awk 'BEGIN { for (i=0;i<16001;i++) printf \"x\" }'")
                .put("identity", "user")
                .put("cwd", temporaryFolder.root.absolutePath)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.toString(), result.getBoolean("stdout_truncated"))
            assertFalse(result.getString("stdout").contains("__ETA_RUNTIME_"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun runtimeProbeHandlesPartialHeaderAndDistinguishesGuestUid() {
        val marker = TerminalExecutionProbe.marker()
        assertEquals("", TerminalExecutionProbe.extract(marker.take(10), marker, "user", TerminalEnvironment.ANDROID).text)
        val text = "$marker:begin\nuid=0\nprovider=sh\ncommand=sh\n$marker:end\nhello"
        val parsed = TerminalExecutionProbe.extract(text, marker, "user", TerminalEnvironment.ALPINE)
        assertEquals("hello", parsed.text)
        assertEquals("guest", parsed.runtime!!.getString("uid_scope"))
        assertEquals("user", parsed.runtime.getString("host_identity"))
    }

    @Test
    fun linuxEnvironmentRequiresInstallationAndRootIdentity() {
        val missingController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = File(temporaryFolder.root, "missing-rootfs").absolutePath,
        )
        try {
            val missing = JSONObject(
                missingController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "python3 --version")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("timeout_ms", 5_000)
                    .put("identity", "root")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
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
                readyController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "id")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
                ),
            )
            assertFalse(user.toString(), user.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_REQUIRES_ROOT", user.getString("code"))
        } finally {
            readyController.closeAll()
        }

        var requestedEnvironment: TerminalEnvironment? = null
        val debianController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPathProvider = { environment ->
                requestedEnvironment = environment
                if (environment == TerminalEnvironment.DEBIAN) {
                    File(temporaryFolder.root, "missing-debian-rootfs").absolutePath
                } else {
                    null
                }
            },
            selectedLinuxEnvironmentProvider = { TerminalEnvironment.DEBIAN },
        )
        try {
            val result = JSONObject(
                debianController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "python3 --version")
                    .put("timeout_ms", 5_000)
                    .put("identity", "root")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
                ),
            )
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", result.getString("code"))
            assertEquals(TerminalEnvironment.DEBIAN, requestedEnvironment)
        } finally {
            debianController.closeAll()
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
