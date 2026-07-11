package fuck.andes.agent.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    private val eventSink: (AgentEvent) -> Unit = {},
    private val resultSink: (AgentRuntimeWire.RunResult) -> Unit = {},
) {
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var state = State.RUNNING

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            eventSink(event)
            true
        }

    fun steer(text: String): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            controller.steer(text)
        }

    fun <T : AgentEvent> steer(
        text: String,
        eventFactory: () -> T,
    ): T? =
        lock.withLock {
            if (state != State.RUNNING || !controller.steer(text)) return null
            eventFactory().also(eventSink)
        }

    /**
     * 先原子竞争 COMMITTING，再完成提交前副作用和结果发布。取消与替换不能越过提交胜者，
     * 因而不会出现“客户端收到取消、outbox 却留下成功结果”的分裂状态；耗时 I/O 也不持有锁。
     * [beforePublish] 必须自行吸收非致命持久化异常。
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: () -> Unit = {},
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            state = State.COMMITTING
        }
        val commitFailure = runCatching(beforePublish).exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
            resultSink(result)
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
            )
        }
        controller.cancel()
        resultSink(result)
        return true
    }
}
