package io.github.mangi.eta.agent.model.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/** 仅在单次 Agent run 内传递，稳定会话 codec 不认识并会主动丢弃。 */
internal object ResponsesEphemeralState {
    private const val OUTPUT_ITEMS_KEY = "_eta_responses_output_items"

    fun outputItems(message: JSONObject): JSONArray? =
        message.optJSONArray(OUTPUT_ITEMS_KEY)

    fun attachOutputItems(message: JSONObject, items: JSONArray) {
        message.put(OUTPUT_ITEMS_KEY, JSONArray(items.toString()))
    }

    fun copyOutputItems(source: JSONObject, target: JSONObject) {
        outputItems(source)?.let { attachOutputItems(target, it) }
    }
}
