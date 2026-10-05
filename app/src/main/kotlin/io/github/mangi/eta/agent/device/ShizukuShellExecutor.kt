package io.github.mangi.eta.agent.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku

/**
 * 经 Shizuku UserService 以 shell 身份执行固定命令。
 *
 * 与 [BoundedRootCommandExecutor] 对称：同样的超时/截断语义，返回同一 [Result] 类型，
 * 便于 [PrivilegedCommandExecutor] 做 Root→Shizuku 降级。调用方同样不得把模型参数
 * 直接拼成脚本（使用 shellQuote 后的固定命令）。
 *
 * 不可用时返回 `SHIZUKU_UNAVAILABLE`；binder 调用失败返回 `SHIZUKU_COMMAND_FAILED`。
 */
internal class ShizukuShellExecutor(
    private val context: Context,
    private val logger: AgentLogger,
    private val shizukuAvailable: () -> Boolean = { ShizukuAccess.isAvailable },
) {
    fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): BoundedRootCommandExecutor.Result {
        if (!shizukuAvailable()) return BoundedRootCommandExecutor.Result.failed("SHIZUKU_UNAVAILABLE")
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return BoundedRootCommandExecutor.Result.failed("SHIZUKU_UNAVAILABLE")
        }
        val appContext = context.applicationContext
        val binderRef = AtomicReference<IBinder>()
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuUserService::class.java.name),
        ).processNameSuffix("shizuku").version(SERVICE_VERSION)
        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onFailure {
            logger.warn("Shizuku shell outcome=bind_failed")
        }
        // bindUserService 无返回值（异常即失败）；等待 binder 回调。
        val connected = runCatching {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return try {
            val binder = binderRef.get()
            if (!connected || binder == null || !runCatching { binder.isBinderAlive }.getOrDefault(false)) {
                logger.warn("Shizuku shell outcome=bind_failed")
                return BoundedRootCommandExecutor.Result.failed("SHIZUKU_UNAVAILABLE")
            }
            transact(binder, command, timeoutMillis, maxOutputBytes)
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private fun transact(
        binder: IBinder,
        command: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): BoundedRootCommandExecutor.Result {
        val capped = maxOutputBytes.coerceIn(1, ShizukuUserService.MAX_OUTPUT_BYTES)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuUserService.DESCRIPTOR)
            data.writeString(command)
            data.writeLong(timeoutMillis.coerceIn(500L, ShizukuUserService.MAX_TIMEOUT_MS))
            data.writeInt(capped)
            runCatching {
                binder.transact(ShizukuUserService.TRANSACTION_EXEC, data, reply, 0)
            }.onFailure {
                logger.warn("Shizuku shell outcome=transact_failed")
                return BoundedRootCommandExecutor.Result.failed("SHIZUKU_COMMAND_FAILED")
            }
            reply.readException()
            val exitCode = reply.readInt()
            val stdout = reply.readString().orEmpty()
            val stderr = reply.readString().orEmpty()
            val timedOut = reply.readInt() != 0
            val truncated = reply.readInt() != 0
            if (exitCode == -1 && (stderr == "CALLER_REJECTED" || stderr == "COMMAND_REJECTED")) {
                return BoundedRootCommandExecutor.Result.failed("SHIZUKU_COMMAND_REJECTED")
            }
            BoundedRootCommandExecutor.Result(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                timedOut = timedOut,
                truncated = truncated,
            )
        } catch (_: Exception) {
            BoundedRootCommandExecutor.Result.failed("SHIZUKU_COMMAND_FAILED")
        } finally {
            runCatching { data.recycle() }
            runCatching { reply.recycle() }
        }.also { result ->
            logger.debug {
                "Shizuku shell outcome=${if (result.ok) "completed" else "failed"} " +
                    "exit=${result.exitCode} timeout=${result.timedOut}"
            }
        }
    }

    private companion object {
        const val SERVICE_VERSION = 1
        const val BIND_TIMEOUT_MS = 10_000L
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    }
}
