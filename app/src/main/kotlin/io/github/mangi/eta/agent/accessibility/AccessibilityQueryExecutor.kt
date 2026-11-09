package io.github.mangi.eta.agent.accessibility

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 查询超时不能中断 Binder；旧查询退出前拒绝新查询，避免线程和请求积压。仅运行无副作用读取。 */
internal class AccessibilityQueryExecutor : AutoCloseable {
    sealed interface Result<out T> {
        data class Completed<T>(val value: T) : Result<T>
        data class Failed(val error: Throwable) : Result<Nothing>
        data object Busy : Result<Nothing>
        data object TimedOut : Result<Nothing>
        data object Closed : Result<Nothing>
    }

    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private class QueryContext(val isActive: () -> Boolean) {
        val cancelled = AtomicBoolean(false)
    }
    private val currentQuery = ThreadLocal<QueryContext>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "agent-accessibility-query").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun <T> query(timeoutMillis: Long, isActive: () -> Boolean = { true }, block: () -> T): Result<T> {
        if (closed.get()) return Result.Closed
        if (Thread.currentThread().isInterrupted) return Result.TimedOut
        if (!busy.compareAndSet(false, true)) return Result.Busy
        val context = QueryContext(isActive)
        val task = FutureTask {
            currentQuery.set(context)
            try {
                ensureActive()
                block().also { ensureActive() }
            } finally {
                currentQuery.remove()
                busy.set(false)
            }
        }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            busy.set(false)
            return if (closed.get()) Result.Closed else Result.Busy
        }
        return try {
            val result = task.get(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (closed.get()) Result.Closed else Result.Completed(result)
        } catch (_: TimeoutException) {
            context.cancelled.set(true)
            Result.TimedOut
        } catch (_: InterruptedException) {
            context.cancelled.set(true)
            Thread.currentThread().interrupt()
            Result.TimedOut
        } catch (error: ExecutionException) {
            if (error.cause is CancellationException) Result.TimedOut else Result.Failed(error.cause ?: error)
        }
    }

    /** 当前 Binder 返回后，遍历必须在下一次远端读取前检查取消；不依赖 interrupt 能中断 Binder。 */
    fun ensureActive() {
        val context = currentQuery.get() ?: return
        if (closed.get() || context.cancelled.get() || !context.isActive() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Accessibility query expired")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }
}
