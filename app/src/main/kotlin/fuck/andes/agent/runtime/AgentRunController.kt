package fuck.andes.agent.runtime

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    @Volatile
    private var steerText: String? = null

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    @Volatile
    private var paused = false

    fun cancel() {
        lock.withLock {
            cancelled = true
            steerText = null
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    fun steer(text: String) {
        val prompt = text.trim()
        if (prompt.isBlank()) return
        lock.withLock {
            if (cancelled) return
            steerText = listOfNotNull(steerText, prompt)
                .joinToString(separator = "\n\n")
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        var pendingSteer: String? = null
        lock.withLock {
            while (paused && !cancelled && steerText == null) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            pendingSteer = steerText
            if (pendingSteer != null) {
                steerText = null
            }
        }
        pendingSteer?.let { throw AgentRunSteeredException(it) }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled || steerText != null) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        fun cancel() = cancelBlock()
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")

internal class AgentRunSteeredException(val supplement: String) : RuntimeException("Agent run steered")
