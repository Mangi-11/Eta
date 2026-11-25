package io.github.mangi.eta.agent.terminal.files

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.shellQuote

class FileToolIntegrationTest {
    @Test fun explicitUserFilesKeepUtf8PaginationAndRejectStaleEditsWithRootAvailable() {
        val directory = File(TerminalRuntime.userWorkspacePath, "file-contract-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "中文 '$' config.txt").apply { writeText("甲乙丙丁\n".repeat(6000)) }
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { true })
        try {
            val first = JSONObject(controller.fileTool("read_file", JSONObject().put("path", file.path).put("identity", "user")
                .put("max_bytes", 65536)))
            assertTrue(first.toString(), first.getBoolean("ok"))
            assertEquals("user", first.getString("identity"))
            assertTrue(first.getBoolean("truncated"))
            assertFalse(first.getString("content").contains('\uFFFD'))
            val second = JSONObject(controller.fileTool("read_file", JSONObject().put("path", file.path).put("identity", "user")
                .put("offset_bytes", first.getLong("next_offset_bytes"))
                .put("expected_revision", first.getString("revision"))))
            assertTrue(second.toString(), second.getBoolean("ok"))
            file.appendText("外部更新")
            val stale = JSONObject(controller.fileTool("edit_file", JSONObject().put("path", file.path).put("identity", "user")
                .put("old_text", "甲").put("new_text", "己")
                .put("replace_all", true).put("expected_revision", first.getString("revision"))))
            assertEquals("FILE_CHANGED", stale.getString("code"))
            assertTrue(file.readText().startsWith("甲"))
        } finally { controller.close(); directory.deleteRecursively() }
    }

    @Test fun unsupportedReferencesAndRevokedRootDoNotRunFileCommands() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val uri = JSONObject(controller.fileTool("read_file", JSONObject().put("path", "content://example/document/1")))
            assertEquals("UNSUPPORTED_FILE_REFERENCE", uri.getString("code"))
            val privileged = JSONObject(controller.fileTool("write_file", JSONObject().put("path", "/root/private")
                .put("content", "x").put("identity", "root")))
            assertEquals("ROOT_REQUIRED", privileged.getString("code"))
            controller.close()
            val cancelled = JSONObject(controller.fileTool("list_directory", JSONObject()))
            assertEquals("CANCELLED", cancelled.getString("code"))
        } finally { controller.close() }
    }

    @Test fun fileAndCommandDefaultsUseTheSameWorkspace() {
        for (rootGranted in listOf(false)) {
            val directory = File(TerminalRuntime.userWorkspacePath, "file-tool-${UUID.randomUUID()}")
            val path = "${directory.name}/内存管理 手册.md"
            val content = "# 内存管理\n" + "中文正文与 shell 字符：${'$'}()、`、'、\"。\n".repeat(300)
            val controller = RootShellTerminalController(NoopLogger, rootAvailable = { rootGranted })
            try {
                val written = JSONObject(controller.fileTool("write_file", JSONObject()
                    .put("path", path).put("content", content)))
                assertTrue(written.toString(), written.getBoolean("ok"))
                assertEquals("user", written.getString("identity"))

                val fromShell = JSONObject(controller.executeTool("terminal", JSONObject()
                    .put("command", "cat ${shellQuote(path)}")))
                assertTrue(fromShell.toString(), fromShell.getBoolean("ok"))
                assertEquals("user", fromShell.getString("identity"))
                assertEquals(content.trimEnd(), fromShell.getString("stdout"))

                val fromFile = JSONObject(controller.fileTool("read_file", JSONObject().put("path", path)))
                assertTrue(fromFile.toString(), fromFile.getBoolean("ok"))
                assertEquals(written.getString("path"), fromFile.getString("path"))
                assertEquals(content, File(written.getString("path")).readText())
            } finally {
                controller.closeAll()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun confirmedRootIsUsedByDefaultWithoutRetryingAsApp() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { true },
            processSupervisor = ShellProcessSupervisor(rootAvailable = { false }))
        val file = File(TerminalRuntime.userWorkspacePath, "root-policy-${UUID.randomUUID()}.txt")
        try {
            val command = JSONObject(controller.executeTool("terminal", JSONObject().put("command", "printf root-only")))
            assertFalse(command.toString(), command.getBoolean("ok"))
            val write = JSONObject(controller.fileTool("write_file", JSONObject().put("path", file.name).put("content", "root-only")))
            assertFalse(write.toString(), write.getBoolean("ok"))
            assertFalse(file.exists())
            val explicitUser = JSONObject(controller.fileTool("write_file", JSONObject()
                .put("path", file.name).put("content", "app").put("identity", "user")))
            assertTrue(explicitUser.toString(), explicitUser.getBoolean("ok"))
            assertEquals(file.canonicalPath, explicitUser.getString("path"))
            assertEquals("app", file.readText())
        } finally { controller.closeAll(); file.delete() }
    }

    @Test fun losingRootRejectsExplicitPrivilegedFileAndCommandCalls() {
        var rootGranted = true
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { rootGranted })
        val file = File(TerminalRuntime.userWorkspacePath, "file-tool-${UUID.randomUUID()}.txt")
        try {
            rootGranted = false
            val written = JSONObject(controller.fileTool("write_file", JSONObject()
                .put("identity", "root").put("path", file.name).put("content", "must not move")))
            assertFalse(written.toString(), written.getBoolean("ok"))
            assertEquals("ROOT_REQUIRED", written.getString("code"))
            assertFalse(file.exists())

            val command = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("identity", "root").put("command", "printf must-not-run")))
            assertFalse(command.toString(), command.getBoolean("ok"))
            assertEquals("ROOT_REQUIRED", command.getString("code"))
        } finally {
            controller.closeAll()
            file.delete()
        }
    }

    @Test fun changingLinuxSettingsDoesNotReplaceTheRunningContext() {
        var environment = TerminalEnvironment.DEBIAN
        val selectedRootfs = File(TerminalRuntime.userWorkspacePath,
            "not-installed-${UUID.randomUUID()}/terminal-user/proot/debian/rootfs").path
        var rootfs = selectedRootfs
        var rootfsReads = 0
        val controller = RootShellTerminalController(
            NoopLogger,
            rootAvailable = { false },
            selectedLinuxEnvironmentProvider = { environment },
            linuxRootfsPathProvider = { rootfsReads++; rootfs },
        )
        try {
            environment = TerminalEnvironment.ALPINE
            rootfs = "/a-different-rootfs"
            assertEquals(TerminalEnvironment.DEBIAN, controller.executionContext.linuxEnvironment)
            assertEquals(selectedRootfs, controller.executionContext.linuxRootfsPath)
            assertEquals("user", controller.executionContext.linuxIdentity)

            val result = JSONObject(controller.fileTool("write_file", JSONObject()
                .put("environment", "linux").put("path", "notes.md").put("content", "same context")))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", result.getString("code"))
            assertEquals(1, rootfsReads)
        } finally { controller.close() }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
