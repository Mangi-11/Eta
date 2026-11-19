package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 签名与搜索步骤只属于当前 run；稳定会话 codec 不读取这些字段。 */
internal object GeminiEphemeralState {
    private const val STEPS = "_eta_gemini_steps"
    private const val VERTEX_PARTS = "_eta_vertex_parts"
    private const val RUN_START = "_eta_gemini_run_start"
    private const val PENDING_SUGGESTIONS = "_eta_gemini_pending_suggestions"

    fun steps(message: JSONObject): JSONArray? = message.optJSONArray(STEPS)

    fun attachSteps(message: JSONObject, steps: JSONArray) {
        message.put(STEPS, JSONArray(steps.toString()))
    }

    fun vertexParts(message: JSONObject): JSONArray? = message.optJSONArray(VERTEX_PARTS)

    fun attachVertexParts(message: JSONObject, parts: JSONArray) {
        message.put(VERTEX_PARTS, JSONArray(parts.toString()))
    }

    fun copyAssistantState(source: JSONObject, target: JSONObject) {
        steps(source)?.let { attachSteps(target, it) }
        vertexParts(source)?.let { attachVertexParts(target, it) }
        attachPendingSuggestions(target, pendingSuggestions(source))
    }

    fun pendingSuggestions(message: JSONObject): JSONArray =
        message.optJSONArray(PENDING_SUGGESTIONS) ?: JSONArray()

    fun attachPendingSuggestions(message: JSONObject, suggestions: JSONArray) {
        if (suggestions.length() > 0) message.put(PENDING_SUGGESTIONS, JSONArray(suggestions.toString()))
    }

    fun markRunStart(message: JSONObject) {
        message.put(RUN_START, true)
    }

    fun isRunStart(message: JSONObject): Boolean = message.optBoolean(RUN_START)

    fun hasPendingToolResponse(messages: JSONArray): Boolean {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant") {
                val steps = steps(message)
                if (steps != null) {
                    return (0 until steps.length()).any {
                        steps.optJSONObject(it)?.optString("type") == "function_call"
                    }
                }
                val parts = vertexParts(message) ?: return false
                return (0 until parts.length()).any {
                    parts.optJSONObject(it)?.optJSONObject("functionCall") != null
                }
            }
        }
        return false
    }

    fun withoutState(message: JSONObject): JSONObject =
        if (!message.has(STEPS) && !message.has(VERTEX_PARTS) && !message.has(RUN_START) && !message.has(PENDING_SUGGESTIONS)) {
            message
        } else {
            JSONObject(message.toString()).apply {
                remove(STEPS)
                remove(VERTEX_PARTS)
                remove(RUN_START)
                remove(PENDING_SUGGESTIONS)
            }
        }
}
