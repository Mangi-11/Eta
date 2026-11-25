package io.github.mangi.eta.agent.runtime.ipc

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.runtime.archive.recoveryProjection

/** Runtime 事件的稳定 JSON 投影；只编码 IPC 已公开的安全字段。 */
internal object AgentEventJsonCodec {
    fun encode(event: AgentEvent): String {
        require(event !is AgentEvent.SearchSuggestions) { "SEARCH_SUGGESTIONS_NOT_PERSISTABLE" }
        return bundleToJson(AgentRuntimeWire.eventToBundle(event)).toString()
    }

    fun decode(raw: String): AgentEvent? = runCatching {
        AgentRuntimeWire.eventFromBundle(jsonToBundle(JSONObject(raw)))?.recoveryProjection()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) }),
                    )
                }
            }
        }
}
