package io.github.mangi.eta.hook.vivo

/** Keep completed/cancelled turns owned so late native events cannot replace an Eta reply. */
internal class VivoTurnOwnership(private val capacity: Int = 64) {
    private data class Key(val traceId: String, val requestId: String)
    private val turns = linkedSetOf<Key>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun claim(turn: VivoReplyProtocol.Turn): Boolean {
        if (turn.traceId.isBlank() || turn.requestId.isBlank()) return false
        if (!turns.add(Key(turn.traceId, turn.requestId))) return false
        while (turns.size > capacity) turns.remove(turns.first())
        return true
    }

    @Synchronized
    fun release(turn: VivoReplyProtocol.Turn) {
        turns.remove(Key(turn.traceId, turn.requestId))
    }

    @Synchronized
    fun owns(traceId: String?, requestId: String? = null): Boolean =
        !traceId.isNullOrBlank() && turns.any {
            it.traceId == traceId && (requestId.isNullOrBlank() || it.requestId == requestId)
        }
}
