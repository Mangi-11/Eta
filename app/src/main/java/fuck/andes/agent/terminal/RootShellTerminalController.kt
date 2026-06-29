package fuck.andes.agent.terminal

import fuck.andes.core.ModuleLogger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: ModuleLogger
) {
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
    }

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
        val normalizedIdentity = identity.lowercase()
        require(normalizedIdentity == "root" || normalizedIdentity == "user") {
            "identity 仅支持 root/user"
        }
        val safeCwd = normalizeCwd(cwd)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        logger.info("Breeno terminal $toolName: identity=$normalizedIdentity, cwd=$safeCwd, timeout=${timeout}s, command=${trimmed.preview()}")
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
        logger.info("Breeno terminal read_file: path=$safePath, offset=$offset, max=$limit")
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
        logger.info("Breeno terminal write_file: path=$safePath, append=$append, bytes=${bytes.size}")
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
        logger.info("Breeno terminal list_directory: path=$safePath, hidden=$showHidden, limit=$maxEntries")
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
        val outputThread = thread(name = "breeno-terminal-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = thread(name = "breeno-terminal-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        val stdinThread = thread(name = "breeno-terminal-stdin") {
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

    private class ByteArrayOutputCollector {
        private val output = ByteArrayOutputStream()

        fun readFrom(input: java.io.InputStream) {
            runCatching {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }.onFailure { throwable ->
                if (throwable !is IOException) throw throwable
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
