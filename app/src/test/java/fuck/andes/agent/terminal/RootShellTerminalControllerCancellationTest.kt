package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellTerminalControllerCancellationTest {
    @Test
    fun interruptAllStopsSynchronousCommandWithoutWaitingForTimeout() {
        val controller = RootShellTerminalController(NoOpLogger)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<String>()
        val worker = thread(name = "terminal-cancel-test", isDaemon = true) {
            entered.countDown()
            result.set(
                controller.terminalOpenAndExec(
                    command = "sleep 30",
                    cwd = System.getProperty("java.io.tmpdir"),
                    timeoutMs = 30_000,
                    identity = "user",
                    mergeStderr = false,
                )
            )
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            waitUntilProcessBlocks(worker)
            controller.interruptAll()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(JSONObject(result.get()).getBoolean("ok"))
        } finally {
            controller.closeAll()
            worker.join(2_000)
        }
    }

    @Test
    fun interruptAllStopsDescendantProcess() {
        val controller = RootShellTerminalController(NoOpLogger)
        val childPidFile = File.createTempFile("eta-terminal-child-", ".pid")
        val finished = CountDownLatch(1)
        val result = AtomicReference<String>()
        val worker = thread(name = "terminal-child-cancel-test", isDaemon = true) {
            result.set(
                controller.terminalOpenAndExec(
                    command = "sleep 30 & echo ${'$'}! > ${shellQuote(childPidFile.absolutePath)}; wait",
                    cwd = System.getProperty("java.io.tmpdir"),
                    timeoutMs = 30_000,
                    identity = "user",
                    mergeStderr = false,
                )
            )
            finished.countDown()
        }

        try {
            val childPid = waitForChildPid(childPidFile, worker)
            assertTrue(isProcessRunning(childPid))

            controller.interruptAll()

            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(JSONObject(result.get()).getBoolean("ok"))
            assertTrue(waitUntilProcessExits(childPid))
        } finally {
            controller.closeAll()
            worker.join(2_000)
            childPidFile.delete()
        }
    }

    @Test
    fun completedAsyncJobCleansGrandchildProcess() {
        val controller = newIsolatedGroupController()
        try {
            val started = JSONObject(
                controller.terminalAction(
                    action = "open_and_exec",
                    command = "sh -c 'sleep 30 & echo CHILD_PID=${'$'}!' &",
                    cwd = System.getProperty("java.io.tmpdir"),
                    timeoutMs = 30_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = true,
                    offsetChars = 0,
                    maxChars = 16_000,
                    closeIfDone = false,
                )
            )
            assertTrue(started.getBoolean("ok"))

            val completed = waitForAsyncResult(controller, started.getString("job_id"))
            val childPid = Regex("CHILD_PID=(\\d+)")
                .find(completed.getString("stdout"))
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: error("async 输出缺少 child PID")

            assertTrue(waitUntilProcessExits(childPid))
        } finally {
            controller.closeAll()
        }
    }

    private fun waitUntilProcessBlocks(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            worker.state != Thread.State.WAITING &&
            worker.state != Thread.State.TIMED_WAITING &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
    }

    private fun waitForChildPid(pidFile: File, worker: Thread): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            runCatching { pidFile.readText().trim().toLongOrNull() }
                .getOrNull()
                ?.let { pid -> return pid }
            if (!worker.isAlive) break
            Thread.sleep(10)
        }
        error("未观察到 sleep 子进程")
    }

    private fun waitUntilProcessExits(pid: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (!isProcessRunning(pid)) return true
            Thread.sleep(10)
        }
        return !isProcessRunning(pid)
    }

    private fun waitForAsyncResult(
        controller: RootShellTerminalController,
        jobId: String,
    ): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        do {
            val result = JSONObject(
                controller.terminalAction(
                    action = "read_async_result",
                    command = "",
                    cwd = null,
                    timeoutMs = 1_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = jobId,
                    async = false,
                    offsetChars = 0,
                    maxChars = 16_000,
                    closeIfDone = false,
                )
            )
            if (!result.getBoolean("running")) return result
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("async job 未在预期时间内结束")
    }

    private fun isProcessRunning(pid: Long): Boolean =
        runCatching {
            val process = ProcessBuilder("ps", "-o", "stat=", "-p", pid.toString())
                .redirectErrorStream(true)
                .start()
            val state = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
            process.waitFor(1, TimeUnit.SECONDS)
            state.isNotEmpty() && !state.startsWith("Z")
        }.getOrDefault(false)

    private fun newIsolatedGroupController(): RootShellTerminalController {
        val setsid = File.createTempFile("eta-test-setsid-", ".py").apply {
            writeText(
                """
                #!/usr/bin/python3
                import os
                import sys

                args = sys.argv[1:]
                if args and args[0] == "-w":
                    args = args[1:]
                os.setsid()
                os.execvp(args[0], args)
                """.trimIndent()
            )
            check(setExecutable(true))
            deleteOnExit()
        }
        return RootShellTerminalController(
            logger = NoOpLogger,
            processSupervisor = ShellProcessSupervisor(
                allowTreeFallback = false,
                setsidCommand = setsid.absolutePath,
            ),
        )
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
