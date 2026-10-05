package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage

/** Only remembers Eta's completed turns; it never reads the host's chat database. */
internal class VivoConversationHistory {
    private val sessions = LinkedHashMap<String, MutableList<ConversationMessage>>(8, 0.75f, true)

    @Synchronized
    fun snapshot(sessionId: String): List<ConversationMessage> =
        sessions[sessionId]?.toList().orEmpty()

    @Synchronized
    fun remember(sessionId: String, prompt: String, answer: String) {
        if (sessionId.isBlank() || prompt.length + answer.length > MAX_CHARS) return
        val messages = sessions.getOrPut(sessionId) { mutableListOf() }
        messages += ConversationMessage(role = "user", content = prompt)
        messages += ConversationMessage(role = "assistant", content = answer)
        while (messages.size > MAX_MESSAGES || messages.sumOf { it.content.length } > MAX_CHARS) {
            messages.subList(0, 2).clear()
        }
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
    }

    companion object {
        private const val MAX_MESSAGES = 12
        private const val MAX_CHARS = 48_000
        private const val MAX_SESSIONS = 8
    }
}
