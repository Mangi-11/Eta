package fuck.andes.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentUiHandoffPayload(
    val conversationId: String,
    val supplements: List<Supplement> = emptyList(),
) {
    data class Supplement(
        val index: Int,
        val text: String,
        val createdAt: Long,
    )

    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("conversationId", conversationId)
            .put(
                "supplements",
                JSONArray().also { array ->
                    supplements.forEach { supplement ->
                        array.put(
                            JSONObject()
                                .put("index", supplement.index)
                                .put("text", supplement.text)
                                .put("createdAt", supplement.createdAt)
                        )
                    }
                }
            )
            .toString()

    companion object {
        private const val TYPE = "agent_ui_handoff"
        private const val VERSION = 1

        fun from(raw: String): AgentUiHandoffPayload {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) {
                return AgentUiHandoffPayload(conversationId = trimmed)
            }
            return runCatching {
                val json = JSONObject(trimmed)
                if (json.optString("type") != TYPE) {
                    return@runCatching AgentUiHandoffPayload(conversationId = trimmed)
                }
                val supplementsJson = json.optJSONArray("supplements") ?: JSONArray()
                AgentUiHandoffPayload(
                    conversationId = json.optString("conversationId"),
                    supplements = (0 until supplementsJson.length()).mapNotNull { index ->
                        val item = supplementsJson.optJSONObject(index) ?: return@mapNotNull null
                        val text = item.optString("text").trim()
                        if (text.isBlank()) return@mapNotNull null
                        Supplement(
                            index = item.optInt("index", index + 1),
                            text = text,
                            createdAt = item.optLong("createdAt"),
                        )
                    }
                )
            }.getOrDefault(AgentUiHandoffPayload(conversationId = trimmed))
        }
    }
}
