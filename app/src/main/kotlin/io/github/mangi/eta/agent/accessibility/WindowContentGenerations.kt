package io.github.mangi.eta.agent.accessibility

/** 窗口 ID 跨显示屏唯一；单调序列确保移除或容量淘汰后的同 ID 不会复用旧快照代次。 */
internal class WindowContentGenerations(private val capacity: Int = 256) {
    private val generations = linkedMapOf<Int, Long>()
    private var sequence = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun current(windowId: Int): Long = generations[windowId] ?: advance(windowId)

    @Synchronized
    fun advance(windowId: Int): Long {
        val generation = ++sequence
        generations.remove(windowId)
        generations[windowId] = generation
        while (generations.size > capacity) {
            generations.remove(generations.keys.first())
        }
        return generation
    }

    @Synchronized
    fun remove(windowId: Int) {
        generations.remove(windowId)
    }

    @Synchronized
    fun clear() {
        generations.clear()
    }
}
