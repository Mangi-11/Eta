package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger
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

    fun runCommand(command: String, cwd: String?, timeoutSeconds: Int): String {
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = "root",
            mergeStderr = false,
            toolName = "run_command"
        )
    }

    fun terminalOpenAndExec(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean
    ): String {
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = identity.ifBlank { "root" },
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
        closeIfDone: Boolean
    ): String {
        return when (action.lowercase()) {
            "open" -> openSession(identity = identity, cwd = cwd)
            "exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "open_and_exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
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

    private fun openSession(identity: String, cwd: String?): String {
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { "root" })
        val safeCwd = normalizeCwd(cwd)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity)
            ?: return errorJson("PROCESS_START_FAILED", "无法启动 $normalizedIdentity terminal session")
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
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
        synchronized(sessions) {
            sessions[id] = session
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
            .put("cwd", session.cwd)
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        sessionId: String?,
        async: Boolean
    ): String {
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { "root" })
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
        mergeStderr: Boolean,
        sessionId: String?
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        val safeCwd = normalizeCwd(cwd)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val process = runCatching {
            if (normalizedIdentity == "root") {
                ProcessBuilder("su", "-c", fullCommand).redirectErrorStream(mergeStderr).start()
            } else {
                ProcessBuilder("sh", "-c", fullCommand).redirectErrorStream(mergeStderr).start()
            }
        }.getOrElse {
            return errorJson("PROCESS_START_FAILED", it.message ?: it.javaClass.simpleName)
        }
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
            val finished = process.waitFor(job.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                job.timedOut = true
                process.destroyForcibly()
            }
            job.exitCode = runCatching { process.exitValue() }.getOrDefault(-2)
            job.completedAt = System.currentTimeMillis()
        }
        synchronized(asyncJobs) { asyncJobs[id] = job }
        logger.info("Agent terminal async started: id=$id identity=$normalizedIdentity cwd=$safeCwd command=${trimmed.preview()}")
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open_and_exec")
            .put("async", true)
            .put("job_id", id)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("identity", normalizedIdentity)
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

    fun closeAll() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val jobs = synchronized(asyncJobs) {
            asyncJobs.values.toList().also { asyncJobs.clear() }
        }
        jobs.forEach(::closeJob)
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        if (session.process.isAlive) {
            session.process.destroyForcibly()
        }
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        return true
    }

    private fun closeJob(id: String): Boolean {
        val job = synchronized(asyncJobs) { asyncJobs.remove(id) } ?: return false
        closeJob(job)
        return true
    }

    private fun closeJob(job: AsyncCommand) {
        if (job.exitCode == null && job.process.isAlive) {
            job.process.destroyForcibly()
        }
        runCatching { job.stdoutThread.join(500) }
        runCatching { job.stderrThread.join(500) }
        runCatching { job.waiterThread.join(500) }
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
        logger.info("Agent terminal session exec: id=${session.id}, identity=${session.identity}, timeout=${timeout}ms, command=${trimmed.preview()}")
        val result = runSessionCommand(session, trimmed, timeout)
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
            if (session.process.isAlive) session.process.destroyForcibly()
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
        mergeStderr: Boolean,
        toolName: String
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        val safeCwd = normalizeCwd(cwd)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        logger.info("Agent terminal $toolName: identity=$normalizedIdentity, cwd=$safeCwd, timeout=${timeout}s, command=${trimmed.preview()}")
        val result = if (normalizedIdentity == "root") {
            runSuText(fullCommand, timeoutSeconds = timeout.toLong())
        } else {
            runShText(fullCommand, timeoutSeconds = timeout.toLong())
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
        logger.info("Agent terminal read_file: path=$safePath, offset=$offset, max=$limit")
        val result = runSuBytes(command, timeoutSeconds = 20)
        if (result.exitCode != 0) {
            return errorJson("READ_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
        }
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
        logger.info("Agent terminal write_file: path=$safePath, append=$append, bytes=${bytes.size}")
        val result = runSuTextWithStdin(command, bytes, timeoutSeconds = 20)
        return if (result.exitCode == 0) {
            JSONObject()
                .put("ok", true)
                .put("tool", "write_file")
                .put("path", safePath)
                .put("mode", if (append) "append" else "overwrite")
                .put("bytes_written", bytes.size)
                .toString()
        } else {
            errorJson("WRITE_FAILED", result.stderr.ifBlank { result.output.ifBlank { "exit=${result.exitCode}" } })
        }
    }

    fun listDirectory(path: String, showHidden: Boolean, limit: Int): String {
        val safePath = normalizePath(path.ifBlank { DEFAULT_CWD })
        val maxEntries = limit.coerceIn(1, MAX_LIST_ENTRIES)
        val flags = if (showHidden) "-la" else "-l"
        val command = "cd ${shellQuote(safePath)} && ls $flags | head -n $maxEntries"
        logger.info("Agent terminal list_directory: path=$safePath, hidden=$showHidden, limit=$maxEntries")
        val result = runSuText(command, timeoutSeconds = 15)
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

    private fun startSessionProcess(identity: String): Process? =
        runCatching {
            if (identity == "root") {
                ProcessBuilder("su", "-c", "sh").redirectErrorStream(false).start()
            } else {
                ProcessBuilder("sh").redirectErrorStream(false).start()
            }
        }.getOrNull()

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(timeoutSeconds, stdin = null, "su", "-c", command)
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runShText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(timeoutSeconds, stdin = null, "sh", "-c", command)
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuTextWithStdin(command: String, stdin: ByteArray, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(timeoutSeconds, stdin, "su", "-c", command)
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        val result = runProcess(timeoutSeconds, stdin = null, "su", "-c", command)
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString().trimEnd())
    }

    private fun runProcess(
        timeoutSeconds: Long,
        stdin: ByteArray?,
        vararg command: String
    ): ProcessBytesResult {
        val process = runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            return ProcessBytesResult(-1, ByteArray(0), it.message.orEmpty().toByteArray())
        }

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
            process.destroyForcibly()
            outputThread.join(500)
            stderrThread.join(500)
            stdinThread.join(500)
            return ProcessBytesResult(-2, output.bytes(), "命令执行超时".toByteArray())
        }

        outputThread.join(500)
        stderrThread.join(500)
        stdinThread.join(500)
        return ProcessBytesResult(process.exitValue(), output.bytes(), stderr.bytes())
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) this else take(MAX_OUTPUT_CHARS) + "\n...[truncated]"

    private fun String.preview(): String =
        replace('\n', ' ').replace('\r', ' ').let { if (it.length > 160) it.take(160) + "..." else it }

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
    }

    private class AsyncCommand(
        val id: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
        val command: String,
        val cwd: String,
        val identity: String,
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
