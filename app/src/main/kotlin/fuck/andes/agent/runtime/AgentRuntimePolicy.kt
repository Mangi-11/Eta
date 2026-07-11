package fuck.andes.agent.runtime

import android.content.SharedPreferences
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.config.Prefs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Runtime 自己裁决可选能力，不能把入口进程提交的布尔值当作授权。 */
internal object AgentRuntimePolicy {
    data class Permissions(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val thinking: Boolean,
    )

    fun permissions(preferences: SharedPreferences?): Permissions =
        Permissions(
            terminalTools = preferences.allowed(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = preferences.allowed(Prefs.Keys.AGENT_BROWSER_TOOLS),
            thinking = preferences.allowed(Prefs.Keys.AGENT_THINKING_ENABLED),
        )

    fun constrain(
        config: AgentModelClient.ModelConfig,
        permissions: Permissions,
    ): AgentModelClient.ModelConfig {
        val thinkingEnabled = config.thinkingEnabled && permissions.thinking
        val constrained = config.copy(
            terminalTools = config.terminalTools && permissions.terminalTools,
            browserTools = config.browserTools && permissions.browserTools,
            thinkingEnabled = thinkingEnabled,
        )
        if (thinkingEnabled) return constrained
        return constrained.copy(
            extraBodyJson = stripThinkingOverrides(constrained.extraBodyJson),
            customBody = constrained.customBody.mapNotNull { body ->
                if (body.key.isThinkingKey()) return@mapNotNull null
                body.copy(value = body.value.stripThinkingOverrides())
            },
        )
    }

    private fun SharedPreferences?.allowed(key: String): Boolean {
        if (this == null) return false
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: false
        return runCatching { getBoolean(key, default) }.getOrDefault(false)
    }

    private fun stripThinkingOverrides(raw: String): String {
        if (raw.isBlank()) return raw
        return runCatching {
            JSONObject(raw).also { it.stripThinkingOverrides() }.toString()
        }.getOrDefault(raw)
    }

    private fun JSONObject.stripThinkingOverrides() {
        val keys = keys().asSequence().toList()
        keys.forEach { key ->
            if (key.isThinkingKey()) {
                remove(key)
                return@forEach
            }
            when (val child = opt(key)) {
                is JSONObject -> child.stripThinkingOverrides()
                is org.json.JSONArray -> {
                    for (index in 0 until child.length()) {
                        (child.opt(index) as? JSONObject)?.stripThinkingOverrides()
                    }
                }
            }
        }
    }

    private fun JsonElement.stripThinkingOverrides(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(
                entries
                    .filterNot { (key, _) -> key.isThinkingKey() }
                    .associate { (key, value) -> key to value.stripThinkingOverrides() }
            )
            is JsonArray -> JsonArray(map { it.stripThinkingOverrides() })
            else -> this
        }

    private fun String.isThinkingKey(): Boolean =
        lowercase().replace('-', '_') in THINKING_OVERRIDE_KEYS

    private val THINKING_OVERRIDE_KEYS = setOf(
        "thinking",
        "thinking_budget",
        "reasoning",
        "reasoning_effort",
        "reasoning_budget",
        "reasoning_max_tokens",
        "enable_thinking",
    )
}
