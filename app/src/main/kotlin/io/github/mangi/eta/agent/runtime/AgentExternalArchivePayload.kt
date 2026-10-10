package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONObject

/**
 * Generic handoff payload for runs initiated outside the first-party Agent UI.
 *
 * Entry adapters may include their own opaque adapter payload, but the core UI
 * only reads the archive fields below. This keeps app-specific adapter details
 * out of the Agent conversation model.
 */
internal data class AgentExternalArchivePayload(
    val userText: String,
    val conversationKey: String,
    val title: String,
    val thinkingEnabled: Boolean? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val adapterPayload: JSONObject = JSONObject(),
) {
    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("userText", userText)
            .put("conversationKey", conversationKey)
            .put("title", title)
            .also { json ->
                (thinkingEnabled ?: reasoningEffort?.enablesReasoning)
                    ?.let { json.put("thinkingEnabled", it) }
                reasoningEffort?.let { json.put("reasoningEffort", it.wireValue) }
                if (adapterPayload.length() > 0) {
                    json.put("adapterPayload", adapterPayload)
                }
            }
            .toString()

    companion object {
        private const val TYPE = "external_archive"
        private const val VERSION = 1

        fun conversationId(source: String, conversationKey: String): String {
            val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) "assistant-" else "archive-"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            return prefix + digest
        }

        fun from(raw: String): AgentExternalArchivePayload? =
            runCatching {
                val json = JSONObject(raw)
                if (json.optString("type") != TYPE) return null
                AgentExternalArchivePayload(
                    userText = json.optString("userText"),
                    conversationKey = json.optString("conversationKey"),
                    title = json.optString("title"),
                    thinkingEnabled = if (json.has("thinkingEnabled") && !json.isNull("thinkingEnabled")) {
                        json.optBoolean("thinkingEnabled")
                    } else {
                        null
                    },
                    reasoningEffort = if (json.has("reasoningEffort") && !json.isNull("reasoningEffort")) {
                        ReasoningEffort.fromWireValue(json.optString("reasoningEffort"))
                            ?: ReasoningEffort.DEFAULT
                    } else {
                        null
                    },
                    adapterPayload = json.optJSONObject("adapterPayload") ?: JSONObject(),
                )
            }.getOrNull()?.takeIf { payload ->
                payload.userText.isNotBlank() && payload.conversationKey.isNotBlank()
            }
    }
}
