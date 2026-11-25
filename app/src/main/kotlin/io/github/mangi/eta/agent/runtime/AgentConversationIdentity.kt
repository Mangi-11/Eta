package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return "run:$fallbackRunId"
    }

    fun archiveConversationId(source: String, conversationKey: String): String {
        val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) "assistant-" else EXTERNAL_ARCHIVE_PREFIX
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return prefix + digest
    }
}
