package fuck.andes.agent.accessibility

import java.util.concurrent.atomic.AtomicReference

/** 防止同步桥超时返回后，尚未开始的 UI 动作又迟到执行。 */
internal class MainThreadCallGate {
    enum class State {
        PENDING,
        RUNNING,
        FINISHED,
        CANCELLED,
    }

    private val state = AtomicReference(State.PENDING)

    fun tryStart(): Boolean = state.compareAndSet(State.PENDING, State.RUNNING)

    fun finish() {
        state.compareAndSet(State.RUNNING, State.FINISHED)
    }

    fun cancelIfPending(): Boolean = state.compareAndSet(State.PENDING, State.CANCELLED)

    fun currentState(): State = state.get()
}
