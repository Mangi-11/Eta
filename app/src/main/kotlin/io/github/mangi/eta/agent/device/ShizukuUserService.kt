package io.github.mangi.eta.agent.device

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 经 Shizuku 以 shell 身份启动的用户服务。
 *
 * 启动方式（客户端见 [ShizukuShellExecutor]）：
 * `Shizuku.bindUserService(UserServiceArgs(ComponentName(pkg, ...ShizukuUserService)).processNameSuffix("shizuku"), conn)`。
 * Shizuku 会在 shell（ADB/Root）身份的新进程中实例化本服务，因此 [onBind] 返回的 Binder
 * 内执行的 `sh -c` 命令拥有 shell 权限，可运行 `settings/cmd/pm/logcat/dumpsys` 等 ADB 级命令。
 *
 * 安全说明：
 * - 本服务 `exported=true` 是 Shizuku 启动所必需，但 [ShellBinder.onTransact] 会校验
 *   调用方 UID：仅放行自身 UID（含 Shizuku 进程内回调用）、Eta 应用 UID、shell(2000)、
 *   root(0)、system(1000)。第三方应用直接 bind 会因 UID 不在白名单被拒绝，
 *   无法借 Eta 身份读取私有数据或借用已授予的运行时权限。
 * - 服务端只执行调用方内部构造的固定命令；模型参数不得直接拼接，调用链复用
 *   [BoundedRootCommandExecutor] 的“固定命令 + shellQuote”约定。
 * - 私有数据库快照（alarm/clipboard/health/aimemory）、WifiConfigStore 等仅 Root 可读路径
 *   不得经由此通道下发，维持 Root 专用。
 */
class ShizukuUserService : Service() {

    override fun onBind(intent: Intent?): IBinder = ShellBinder(this)

    private class ShellBinder(private val service: Service) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACTION_EXEC) {
                if (!isCallerAllowed()) {
                    reply?.apply {
                        writeNoException()
                        writeInt(-1)
                        writeString("")
                        writeString("CALLER_REJECTED")
                        writeInt(0)
                        writeInt(0)
                    }
                    return true
                }
                data.enforceInterface(DESCRIPTOR)
                val command = data.readString().orEmpty()
                val timeoutMillis = data.readLong().coerceIn(500L, MAX_TIMEOUT_MS)
                val maxOutputBytes = data.readInt().coerceIn(1, MAX_OUTPUT_BYTES)
                val result = runShell(command, timeoutMillis, maxOutputBytes)
                reply?.apply {
                    writeNoException()
                    writeInt(result.exitCode)
                    writeString(result.stdout)
                    writeString(result.stderr)
                    writeInt(if (result.timedOut) 1 else 0)
                    writeInt(if (result.truncated) 1 else 0)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private fun isCallerAllowed(): Boolean {
            val callingUid = getCallingUid()
            if (callingUid == Process.myUid()) return true
            val appUid = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    service.packageManager.getPackageUid(
                        service.packageName,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    service.packageManager.getPackageUid(service.packageName, 0)
                }
            }.getOrDefault(Process.INVALID_UID)
            return callingUid == appUid ||
                callingUid == Process.SHELL_UID ||
                callingUid == Process.ROOT_UID ||
                callingUid == Process.SYSTEM_UID
        }

        private fun runShell(command: String, timeoutMillis: Long, maxBytes: Int): ShellResult {
            if (command.isBlank() || command.length > MAX_COMMAND_CHARS) {
                return ShellResult(-1, "", "COMMAND_REJECTED", timedOut = false, truncated = false)
            }
            // 每流预算为总额一半：reply Parcel 经 Binder 约 1MB 上限，UTF-16 下
            // 256KB 总量是安全水位；超量调用方（如 dumpsys 2MB）会被截断并标记 truncated。
            val perStream = (maxBytes / 2).coerceIn(1, MAX_OUTPUT_BYTES)
            val process = runCatching {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()
            }.getOrElse {
                return ShellResult(-1, "", "EXEC_FAILED", timedOut = false, truncated = false)
            }
            // 双流并发排空：输出超管道缓冲时仍持续读取，避免子进程阻塞导致误判超时
            //（与 BoundedRootCommandExecutor 同模式）。
            val ioPool = Executors.newFixedThreadPool(2)
            return try {
                val stdoutFuture = ioPool.submit<Pair<String, Boolean>> {
                    process.inputStream.use { readBounded(it, perStream) }
                }
                val stderrFuture = ioPool.submit<Pair<String, Boolean>> {
                    process.errorStream.use { readBounded(it, perStream) }
                }
                val completed = runCatching {
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!completed) {
                    runCatching { process.destroy() }
                    runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }
                    if (process.isAlive) runCatching { process.destroyForcibly() }
                }
                val stdout = runCatching {
                    stdoutFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault("" to false)
                val stderr = runCatching {
                    stderrFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault("" to false)
                ShellResult(
                    exitCode = if (completed) runCatching { process.exitValue() }.getOrDefault(-1) else -2,
                    stdout = stdout.first,
                    stderr = stderr.first,
                    timedOut = !completed,
                    truncated = stdout.second || stderr.second,
                )
            } finally {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                ioPool.shutdownNow()
            }
        }

        private fun readBounded(stream: InputStream, maxBytes: Int): Pair<String, Boolean> {
            val collected = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
            val buffer = ByteArray(8 * 1024)
            var truncated = false
            while (true) {
                val read = runCatching { stream.read(buffer) }.getOrNull() ?: break
                if (read < 0) break
                val remaining = maxBytes - collected.size()
                if (remaining > 0) collected.write(buffer, 0, read.coerceAtMost(remaining))
                if (read > remaining) truncated = true
            }
            return decodeTruncated(collected.toByteArray(), truncated) to truncated
        }

        /** 按字节截断后回退到合法 UTF-8 边界，避免尾字变 U+FFFD。 */
        private fun decodeTruncated(bytes: ByteArray, truncated: Boolean): String {
            if (!truncated) return bytes.toString(Charsets.UTF_8)
            var end = bytes.size
            // 最多回退 3 字节：找到被截断的多字节序列起始位。
            var backtrack = 0
            while (backtrack < 3 && end - backtrack - 1 >= 0) {
                val byte = bytes[end - backtrack - 1].toInt() and 0xFF
                if (byte and 0x80 == 0) break
                if (byte and 0xC0 == 0xC0) {
                    val expected = when {
                        byte and 0xF0 == 0xF0 -> 4
                        byte and 0xE0 == 0xE0 -> 3
                        else -> 2
                    }
                    if (backtrack + 1 < expected) end -= (backtrack + 1)
                    break
                }
                backtrack++
            }
            return bytes.copyOf(end).toString(Charsets.UTF_8)
        }

        private data class ShellResult(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val timedOut: Boolean,
            val truncated: Boolean,
        )
    }

    companion object {
        const val TRANSACTION_EXEC: Int = IBinder.FIRST_CALL_TRANSACTION
        const val DESCRIPTOR = "io.github.mangi.eta.shizuku.shell"
        const val MAX_TIMEOUT_MS = 30_000L
        /** reply 双流总额上限（每流一半），留足 Binder ~1MB 事务余量。 */
        const val MAX_OUTPUT_BYTES = 256 * 1024
        private const val MAX_COMMAND_CHARS = 8 * 1024
        private const val IO_JOIN_TIMEOUT_MS = 2_000L
    }
}
