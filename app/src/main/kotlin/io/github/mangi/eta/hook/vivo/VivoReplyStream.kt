package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Only public answer text is projected; reasoning, tool arguments and failed rounds are excluded. */
internal class VivoReplyStream {
    data class Packet(val text: String, val fullText: String, val index: Int, val isLast: Boolean)
    private val blocks = sortedMapOf<Int, StringBuilder>()
    private var round = -1
    private var sent = ""
    private var index = 0
    private var finished = false

    @Synchronized
    fun accept(event: AgentEvent): Boolean {
        if (finished) return false
        when (event) {
            is AgentEvent.RoundStarted -> { round = event.round; blocks.clear() }
            is AgentEvent.ModelRequestInterrupted -> if (event.round == round) {
                blocks.clear()
                round = -1
                return true
            }
            is AgentEvent.AssistantBlockDelta -> if (event.round == round && event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                val block = blocks.getOrPut(event.index) { StringBuilder() }
                block.append(event.delta.take((MAX_CHARS - block.length).coerceAtLeast(0)))
                return true
            }
            is AgentEvent.AssistantBlockEnd -> if (event.round == round && event.kind == AgentEvent.AssistantBlockKind.TEXT && event.replacementContent != null) {
                blocks[event.index] = StringBuilder(event.replacementContent.take(MAX_CHARS))
                return true
            }
            else -> Unit
        }
        return false
    }

    @Synchronized
    fun flush(): Packet? {
        if (finished) return null
        val text = blocks.values.joinToString("\n\n").take(MAX_CHARS)
        return if (text == sent) null else snapshot(text)
    }

    @Synchronized
    fun complete(text: String): List<Packet> {
        if (finished) return emptyList()
        val full = text.take(MAX_CHARS)
        val packets = mutableListOf<Packet>()
        if (full != sent) packets += snapshot(full)
        packets += Packet("", full, ++index, isLast = true)
        finished = true
        blocks.clear()
        return packets
    }

    private fun snapshot(text: String): Packet {
        val delta = if (text.startsWith(sent)) text.substring(sent.length) else text
        sent = text
        return Packet(delta, text, ++index, isLast = false)
    }

    private companion object { const val MAX_CHARS = 64_000 }
}
