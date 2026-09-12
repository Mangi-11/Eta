package io.github.mangi.eta.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 上下文替换与 transcript 增量独立交付，摘要正文不进入事件流。 */
@Serializable
internal data class AgentContextSnapshot(
    val version: Int = 1,
    val operationId: String,
    val messages: List<AgentModelClient.ConversationMessage>,
    val coveredUserTurns: Int = 0,
    val consumedUserTurns: Int = 0,
    val consumedSupplementCount: Int = 0,
    val consumedTranscriptMessages: Int? = null,
) {
    fun encode(): String = codec.encodeToString(this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(raw: String?): AgentContextSnapshot? {
            if (raw.isNullOrBlank()) return null
            return codec.decodeFromString<AgentContextSnapshot>(raw).also {
                require(it.version == 1 && it.operationId.isNotBlank())
            }
        }
    }
}
