package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
) : AutoCloseable {
    private companion object {
        const val DEFAULT_CWD = "/data/local/tmp/fuck_andes"
        const val USER_STORAGE = "/storage/emulated/0"
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 180
        const val MAX_COMMAND_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_WRITE_BYTES = 512 * 1024
        const val MAX_LIST_ENTRIES = 200
        const val MAX_ASYNC_OUTPUT_CHARS = 64_000
    }

    private val sessions = linkedMapOf<String, TerminalSession>()
    private val asyncJobs = linkedMapOf<String, AsyncCommand>()
    private val cleanupStarted = AtomicBoolean(false)

    fun runCommand(command: String, cwd: String?, timeoutSeconds: Int): String {
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = "root",
            environment = TerminalEnvironment.ANDROID,
            mergeStderr = false,
            toolName = "run_command"
        )
    }

    fun terminalOpenAndExec(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
    ): String {
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = identity.ifBlank { "root" },
            environment = normalizeEnvironment(environment),
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
    }

    fun terminalAction(
        action: String,
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        sessionId: String?,
        jobId: String?,
        async: Boolean,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
    ): String {
        return when (action.lowercase()) {
            "open" -> openSession(identity = identity, cwd = cwd, environment = environment)
            "exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "open_and_exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "read_async_result" -> readAsyncResult(
                jobId = jobId.orEmpty(),
                offsetChars = offsetChars,
                maxChars = maxChars,
                closeIfDone = closeIfDone
            )
            "close" -> closeTerminal(sessionId = sessionId, jobId = jobId)
            else -> errorJson(
                "UNSUPPORTED_TERMINAL_ACTION",
                "terminal action 仅支持 open/exec/open_and_exec/read_async_result/close"
            )
        }
    }

    private fun openSession(identity: String, cwd: String?, environment: String): String {
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { "root" })
        val normalizedEnvironment = normalizeEnvironment(environment)
        environmentPreflight(normalizedIdentity, normalizedEnvironment)?.let { return it }
        val safeCwd = normalizeCwd(cwd)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity, normalizedEnvironment)
            ?: return errorJson(
                "PROCESS_START_FAILED",
                "无法启动 ${normalizedEnvironment.wireName}/$normalizedIdentity terminal session",
            )
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
            environment = normalizedEnvironment,
            cwd = safeCwd,
            createdAt = System.currentTimeMillis(),
            process = process,
            stdout = stdout,
            stderr = stderr
        )
        session.stdoutThread = thread(name = "agent-terminal-session-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input) }
        }
        session.stderrThread = thread(name = "agent-terminal-session-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        session.waiterThread = thread(name = "agent-terminal-session-waiter-$id", isDaemon = true) {
            runCatching { process.waitFor() }
            processSupervisor.retireExitedProcess(process)
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(sessions) { sessions[id] = session }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }

        val mkdirDefault = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val setup = "${mkdirDefault}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1"
        val setupResult = runSessionCommand(session, setup, timeoutMs = 5_000)
        if (setupResult.exitCode != 0 || setupResult.timedOut) {
            closeSession(id)
            return errorJson("SESSION_OPEN_FAILED", setupResult.stderr.ifBlank { "exit=${setupResult.exitCode}" })
        }
        session.cwd = setupResult.cwd ?: safeCwd
        session.stdout.clear()
        session.stderr.clear()
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open")
            .put("session_id", id)
            .put("identity", normalizedIdentity)
            .put("environment", normalizedEnvironment.wireName)
            .put("cwd", session.cwd)
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: String,
        mergeStderr: Boolean,
        sessionId: String?,
        async: Boolean
    ): String {
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { "root" })
        val effectiveEnvironment = session?.environment ?: normalizeEnvironment(environment)
        environmentPreflight(effectiveIdentity, effectiveEnvironment)?.let { return it }
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() } ?: session?.cwd
        if (async) {
            if (session != null) {
                return errorJson(
                    "ASYNC_SESSION_UNSUPPORTED",
                    "async terminal job 不复用持久 session；请省略 session_id，并用 cwd/identity 启动后台命令"
                )
            }
            return startAsyncCommand(
                command = command,
                cwd = effectiveCwd,
                timeoutMs = timeoutMs,
                identity = effectiveIdentity,
                environment = effectiveEnvironment,
                mergeStderr = mergeStderr,
                sessionId = session?.id
            )
        }
        if (session != null) {
            return execInSession(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
                mergeStderr = mergeStderr
            )
        }
        val result = runCommand(
            command = command,
            cwd = effectiveCwd,
            timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
                .coerceIn(1, MAX_TIMEOUT_SECONDS),
            identity = effectiveIdentity,
            environment = effectiveEnvironment,
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
        return result
    }

    private fun startAsyncCommand(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        sessionId: String?
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val process = processSupervisor.startShellProcess(
            identity = normalizedIdentity,
            command = fullCommand,
            mergeStderr = mergeStderr,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        ) ?: return errorJson(
            if (processSupervisor.isClosing) "TERMINAL_CLOSED" else "PROCESS_START_FAILED",
            if (processSupervisor.isClosing) "terminal controller 已关闭" else "无法启动 terminal process",
        )
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val job = AsyncCommand(
            id = id,
            process = process,
            stdout = stdout,
            stderr = stderr,
            command = trimmed,
            cwd = safeCwd,
            identity = normalizedIdentity,
            environment = environment,
            mergeStderr = mergeStderr,
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            timeoutMs = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        )
        job.stdoutThread = thread(name = "agent-terminal-async-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.stderrThread = thread(name = "agent-terminal-async-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.waiterThread = thread(name = "agent-terminal-async-waiter-$id", isDaemon = true) {
            try {
                val finished = process.waitFor(job.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    job.timedOut = true
                    processSupervisor.terminateProcessTree(process)
                }
                job.exitCode = runCatching { process.exitValue() }.getOrDefault(-2)
                job.completedAt = System.currentTimeMillis()
            } finally {
                processSupervisor.retireExitedProcess(process)
            }
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(asyncJobs) { asyncJobs[id] = job }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }
        logger.info(
            "Agent terminal action=open_and_exec outcome=started async=true " +
                "identity=$normalizedIdentity environment=${environment.wireName} " +
                "timeoutMs=${job.timeoutMs} commandChars=${trimmed.length}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open_and_exec")
            .put("async", true)
            .put("job_id", id)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("running", true)
            .toString()
    }

    private fun readAsyncResult(
        jobId: String,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean
    ): String {
        val job = synchronized(asyncJobs) { asyncJobs[jobId] }
            ?: return errorJson("JOB_NOT_FOUND", "未找到 async terminal job：$jobId")
        val stdoutRaw = job.stdout.text()
        val stderrRaw = job.stderr.text()
        val merged = stdoutRaw
        val offset = offsetChars.coerceAtLeast(0).coerceAtMost(merged.length)
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        val slice = merged.substring(offset, (offset + limit).coerceAtMost(merged.length))
        val done = job.exitCode != null
        if (done && closeIfDone) {
            synchronized(asyncJobs) { asyncJobs.remove(jobId) }?.let(::closeJob)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "read_async_result")
            .put("job_id", job.id)
            .put("session_id", job.sessionId ?: JSONObject.NULL)
            .put("environment", job.environment.wireName)
            .put("running", !done)
            .put("exit_code", job.exitCode ?: JSONObject.NULL)
            .put("timed_out", job.timedOut)
            .put("stdout", slice)
            .put("next_offset_chars", offset + slice.length)
            .put("total_chars", merged.length)
            .put("retained_chars", merged.length)
            .put("stdout_total_bytes", job.stdout.totalBytesRead())
            .put("stderr_total_bytes", job.stderr.totalBytesRead())
            .put("truncated", offset + slice.length < merged.length)
            .put("output_truncated", job.stdout.isTruncated() || job.stderr.isTruncated())
            .put("stderr", if (job.mergeStderr) "" else stderrRaw.truncateForJson())
            .put("stdout_truncated", job.stdout.isTruncated())
            .put("stderr_truncated", !job.mergeStderr && job.stderr.isTruncated())
            .toString()
    }

    private fun closeTerminal(sessionId: String?, jobId: String?): String {
        var closedSession = false
        var closedJob = false
        sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            closedSession = closeSession(id)
        }
        jobId?.takeIf { it.isNotBlank() }?.let { id ->
            closedJob = closeJob(id)
        }
        return JSONObject()
            .put("ok", closedSession || closedJob)
            .put("tool", "terminal")
            .put("action", "close")
            .put("closed_session", closedSession)
            .put("closed_job", closedJob)
            .toString()
    }

    override fun close() {
        closeAll()
    }

    /** 取消热路径只封闭新进程接纳；进程树终止和 reader/waiter 回收在后台完成。 */
    fun interruptAll() {
        beginClosing()
        if (cleanupStarted.compareAndSet(false, true)) {
            thread(name = "agent-terminal-cleanup", isDaemon = true) {
                closeAllInternal()
            }
        }
    }

    fun closeAll() {
        beginClosing()
        cleanupStarted.set(true)
        closeAllInternal()
    }

    private fun beginClosing() {
        processSupervisor.beginClosing()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.closed = true }
        }
    }

    private fun closeAllInternal() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val jobs = synchronized(asyncJobs) {
            asyncJobs.values.toList().also { asyncJobs.clear() }
        }
        jobs.forEach(::closeJob)

        val remainingProcesses = processSupervisor.takeRemainingProcesses()
        remainingProcesses.forEach { process ->
            processSupervisor.terminateAndReap(process)
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        processSupervisor.terminateAndReap(session.process)
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        runCatching { session.waiterThread.join(500) }
        processSupervisor.unregisterProcess(session.process)
        return true
    }

    private fun closeJob(id: String): Boolean {
        val job = synchronized(asyncJobs) { asyncJobs.remove(id) } ?: return false
        closeJob(job)
        return true
    }

    private fun closeJob(job: AsyncCommand) {
        processSupervisor.terminateAndReap(job.process)
        runCatching { job.stdoutThread.join(500) }
        runCatching { job.stderrThread.join(500) }
        runCatching { job.waiterThread.join(500) }
        processSupervisor.unregisterProcess(job.process)
    }

    private fun execInSession(
        session: TerminalSession,
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        val result = runSessionCommand(session, trimmed, timeout)
        val outcome = when {
            result.timedOut -> "timed_out"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome session=true " +
                "identity=${session.identity} environment=${session.environment.wireName} " +
                "timeoutMs=$timeout commandChars=${trimmed.length} " +
                "exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        if (result.cwd != null) session.cwd = result.cwd
        if (result.timedOut) {
            closeSession(session.id)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.stdout + "\n[stderr]\n" + result.stderr
        } else {
            result.stdout
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("session_id", session.id)
            .put("identity", session.identity)
            .put("environment", session.environment.wireName)
            .put("cwd", session.cwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .put("session_closed", result.timedOut || session.closed)
            .toString()
    }

    private fun runSessionCommand(
        session: TerminalSession,
        command: String,
        timeoutMs: Int
    ): SessionCommandResult {
        synchronized(session.lock) {
            if (session.closed || !session.process.isAlive) {
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "terminal session 已关闭",
                    cwd = session.cwd,
                    timedOut = false
                )
            }
            val marker = "__ANDES_STATUS_${UUID.randomUUID().toString().replace("-", "")}"
            val stdoutStart = session.stdout.text().length
            val stderrStart = session.stderr.text().length
            val commandBlock = buildString {
                append(command)
                append('\n')
                append("printf '\\n$marker:%s:%s\\n' \"${'$'}?\" \"${'$'}PWD\"")
                append('\n')
            }
            runCatching {
                session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }.getOrElse {
                session.closed = true
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                    stderr = it.message ?: it.javaClass.simpleName,
                    cwd = session.cwd,
                    timedOut = false
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val stdoutDelta = session.stdout.text().drop(stdoutStart)
                if (session.closed || !session.process.isAlive) {
                    return SessionCommandResult(
                        exitCode = -1,
                        stdout = stdoutDelta.trimEnd(),
                        stderr = session.stderr.text().drop(stderrStart).ifBlank { "terminal session 已关闭" }.trimEnd(),
                        cwd = session.cwd,
                        timedOut = false
                    )
                }
                val statusLine = stdoutDelta.lineSequence().firstOrNull { it.startsWith("$marker:") }
                if (statusLine != null) {
                    val status = statusLine.removePrefix("$marker:")
                    val separator = status.indexOf(':')
                    val exitCode = if (separator > 0) status.take(separator).toIntOrNull() ?: -1 else -1
                    val cwd = if (separator > 0) status.drop(separator + 1).ifBlank { session.cwd } else session.cwd
                    val cleanedStdout = stdoutDelta
                        .lineSequence()
                        .filterNot { it.startsWith("$marker:") }
                        .joinToString("\n")
                        .trimEnd()
                    val stderrDelta = session.stderr.text().drop(stderrStart).trimEnd()
                    session.stdout.clear()
                    session.stderr.clear()
                    return SessionCommandResult(
                        exitCode = exitCode,
                        stdout = cleanedStdout,
                        stderr = stderrDelta,
                        cwd = cwd,
                        timedOut = false
                    )
                }
                Thread.sleep(50)
            }

            session.closed = true
            processSupervisor.terminateProcessTree(session.process)
            return SessionCommandResult(
                exitCode = -2,
                stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                stderr = session.stderr.text().drop(stderrStart).ifBlank { "命令执行超时" }.trimEnd(),
                cwd = session.cwd,
                timedOut = true
            )
        }
    }

    private fun runCommand(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        toolName: String
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeout.toLong(),
            environment = environment,
        )
        val outcome = when (result.exitCode) {
            0 -> "succeeded"
            -2 -> "timed_out"
            else -> "failed"
        }
        val action = if (toolName == "terminal") "open_and_exec" else "run_command"
        val logMessage =
            "Agent terminal action=$action outcome=$outcome identity=$normalizedIdentity " +
                "environment=${environment.wireName} " +
                "timeoutSeconds=$timeout commandChars=${trimmed.length} exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.output + "\n[stderr]\n" + result.stderr
        } else {
            result.output
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", toolName)
            .put("action", if (toolName == "terminal") "open_and_exec" else JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.exitCode == -2)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .toString()
    }

    fun readFile(path: String, offsetBytes: Int, maxBytes: Int): String {
        val safePath = normalizePath(path)
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        val command = "dd if=${shellQuote(safePath)} bs=1 skip=$offset count=$limit 2>/dev/null"
        val result = runSuBytes(command, timeoutSeconds = 20)
        if (result.exitCode != 0) {
            logger.warn(
                "Agent terminal action=read_file outcome=failed offsetBytes=$offset " +
                    "maxBytes=$limit exitCode=${result.exitCode} errorChars=${result.stderr.length}"
            )
            return errorJson("READ_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
        }
        logger.info(
            "Agent terminal action=read_file outcome=succeeded offsetBytes=$offset " +
                "maxBytes=$limit bytesRead=${result.output.size} exitCode=${result.exitCode}"
        )
        val text = result.output.decodeToString()
        val truncated = result.output.size >= limit
        return JSONObject()
            .put("ok", true)
            .put("tool", "read_file")
            .put("path", safePath)
            .put("offset_bytes", offset)
            .put("bytes_read", result.output.size)
            .put("truncated", truncated)
            .put("content", text.truncateForJson())
            .toString()
    }

    fun writeFile(path: String, content: String, append: Boolean): String {
        val safePath = normalizePath(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) { "写入内容过大：${bytes.size} bytes" }
        val mode = if (append) ">>" else ">"
        val command = "mkdir -p ${shellQuote(File(safePath).parent ?: "/")} && cat $mode ${shellQuote(safePath)}"
        val result = runSuTextWithStdin(command, bytes, timeoutSeconds = 20)
        return if (result.exitCode == 0) {
            logger.info(
                "Agent terminal action=write_file outcome=succeeded append=$append " +
                    "bytesWritten=${bytes.size} exitCode=${result.exitCode}"
            )
            JSONObject()
                .put("ok", true)
                .put("tool", "write_file")
                .put("path", safePath)
                .put("mode", if (append) "append" else "overwrite")
                .put("bytes_written", bytes.size)
                .toString()
        } else {
            logger.warn(
                "Agent terminal action=write_file outcome=failed append=$append " +
                    "inputBytes=${bytes.size} exitCode=${result.exitCode} " +
                    "outputChars=${result.output.length} errorChars=${result.stderr.length}"
            )
            errorJson("WRITE_FAILED", result.stderr.ifBlank { result.output.ifBlank { "exit=${result.exitCode}" } })
        }
    }

    fun listDirectory(path: String, showHidden: Boolean, limit: Int): String {
        val safePath = normalizePath(path.ifBlank { DEFAULT_CWD })
        val maxEntries = limit.coerceIn(1, MAX_LIST_ENTRIES)
        val flags = if (showHidden) "-la" else "-l"
        val command = "cd ${shellQuote(safePath)} && ls $flags | head -n $maxEntries"
        val result = runSuText(command, timeoutSeconds = 15)
        val logMessage =
            "Agent terminal action=list_directory " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "showHidden=$showHidden limit=$maxEntries exitCode=${result.exitCode} " +
                "outputChars=${result.output.length} errorChars=${result.stderr.length}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "list_directory")
            .put("path", safePath)
            .put("exit_code", result.exitCode)
            .put("entries_text", result.output.truncateForJson())
            .put("stderr", result.stderr.truncateForJson())
            .toString()
    }

    private fun normalizeIdentity(identity: String): String {
        val normalized = identity.ifBlank { "root" }.lowercase()
        require(normalized == "root" || normalized == "user") {
            "identity 仅支持 root/user"
        }
        return normalized
    }

    private fun normalizeEnvironment(environment: String): TerminalEnvironment =
        when (environment.ifBlank { TerminalEnvironment.ANDROID.wireName }.lowercase()) {
            TerminalEnvironment.ANDROID.wireName -> TerminalEnvironment.ANDROID
            TerminalEnvironment.LINUX.wireName -> TerminalEnvironment.LINUX
            else -> throw IllegalArgumentException("environment 仅支持 android/linux")
        }

    private fun environmentPreflight(
        identity: String,
        environment: TerminalEnvironment,
    ): String? = when {
        environment == TerminalEnvironment.LINUX && identity != "root" ->
            errorJson("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
        environment == TerminalEnvironment.LINUX && !AlpineEnvironmentPaths.rootfsReady(linuxRootfsPath) ->
            errorJson(
                "LINUX_ENVIRONMENT_NOT_READY",
                "Linux 工具环境尚未安装，请先在设置中完成环境配置",
            )
        else -> null
    }

    private fun normalizeCwd(cwd: String?): String =
        normalizePath(cwd?.trim().orEmpty().ifBlank { DEFAULT_CWD })

    private fun normalizePath(path: String): String {
        val raw = path.trim()
        require(raw.isNotBlank()) { "path 不能为空" }
        val effective = when {
            raw == "~" -> USER_STORAGE
            raw.startsWith("~/") -> USER_STORAGE + "/" + raw.removePrefix("~/")
            raw.startsWith("/") -> raw
            else -> "$DEFAULT_CWD/$raw"
        }
        val normalized = File(effective).canonicalPath
        require(normalized != "/") { "拒绝直接操作根目录" }
        return normalized
    }

    private fun startSessionProcess(
        identity: String,
        environment: TerminalEnvironment,
    ): Process? =
        processSupervisor.startShellProcess(
            identity = identity,
            command = null,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        )

    private fun runText(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
    ): ShellTextResult {
        val result = runProcess(
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = environment,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd(),
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuTextWithStdin(command: String, stdin: ByteArray, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString().trimEnd())
    }

    private fun runProcess(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        stdin: ByteArray?,
        environment: TerminalEnvironment,
    ): ProcessBytesResult {
        val process = processSupervisor.startShellProcess(
            identity = identity,
            command = command,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        ) ?: return ProcessBytesResult(
            if (processSupervisor.isClosing) -3 else -1,
            ByteArray(0),
            if (processSupervisor.isClosing) "操作已取消".toByteArray() else "无法启动进程".toByteArray(),
        )

        try {
            val output = ByteArrayOutputCollector()
            val stderr = ByteArrayOutputCollector()
            val outputThread = thread(name = "agent-terminal-stdout") {
                process.inputStream.use { input -> output.readFrom(input) }
            }
            val stderrThread = thread(name = "agent-terminal-stderr") {
                process.errorStream.use { input -> stderr.readFrom(input) }
            }
            val stdinThread = thread(name = "agent-terminal-stdin") {
                process.outputStream.use { out ->
                    if (stdin != null) out.write(stdin)
                }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                processSupervisor.terminateProcessTree(process)
                outputThread.join(500)
                stderrThread.join(500)
                stdinThread.join(500)
                processSupervisor.reapProcess(process)
                return ProcessBytesResult(-2, output.bytes(), "命令执行超时".toByteArray())
            }

            outputThread.join(500)
            stderrThread.join(500)
            stdinThread.join(500)
            return ProcessBytesResult(process.exitValue(), output.bytes(), stderr.bytes())
        } finally {
            if (processSupervisor.isClosing) {
                processSupervisor.terminateAndReap(process)
            } else {
                processSupervisor.reapProcess(process)
            }
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) this else take(MAX_OUTPUT_CHARS) + "\n...[truncated]"

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(300))
            .toString()

    private data class ShellTextResult(val exitCode: Int, val output: String, val stderr: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class ProcessBytesResult(val exitCode: Int, val output: ByteArray, val stderr: ByteArray)
    private data class SessionCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val cwd: String?,
        val timedOut: Boolean
    )

    private class TerminalSession(
        val id: String,
        val identity: String,
        val environment: TerminalEnvironment,
        var cwd: String,
        val createdAt: Long,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector
    ) {
        val lock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

    private class AsyncCommand(
        val id: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
        val command: String,
        val cwd: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val mergeStderr: Boolean,
        val sessionId: String?,
        val startedAt: Long,
        val timeoutMs: Int
    ) {
        @Volatile
        var exitCode: Int? = null

        @Volatile
        var timedOut: Boolean = false

        @Volatile
        var completedAt: Long? = null

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

    private class ByteArrayOutputCollector {
        private val output = ByteArrayOutputStream()
        private var totalBytesRead = 0L
        private var truncated = false

        fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
            runCatching {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(this) {
                        totalBytesRead += read.toLong()
                        val allowed = (maxBytes - output.size()).coerceAtLeast(0)
                        if (allowed > 0) {
                            output.write(buffer, 0, read.coerceAtMost(allowed))
                        }
                        if (read > allowed) {
                            truncated = true
                        }
                    }
                }
            }.onFailure { throwable ->
                if (throwable !is IOException) throw throwable
            }
        }

        fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

        fun text(): String = bytes().decodeToString()

        fun totalBytesRead(): Long = synchronized(this) { totalBytesRead }

        fun isTruncated(): Boolean = synchronized(this) { truncated }

        fun clear() {
            synchronized(this) {
                output.reset()
                totalBytesRead = 0
                truncated = false
            }
        }
    }
}
