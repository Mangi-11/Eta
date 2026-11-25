package io.github.mangi.eta.agent.model.provider

import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 将用户自定义请求体字段递归合并到主请求 JSON。
 *
 * 规则：
 * - 如果 key 已存在且两边都是 [JSONObject]，递归合并。
 * - 如果 key 已存在且两边都是 [JSONArray]，替换为用户自定义数组（用户优先）。
 * - 其他情况直接覆盖（用户自定义优先）。
 */
internal object RequestBodyMerge {

    fun mergeCustomBody(target: JSONObject, customBody: List<CustomBody>) {
        customBody.forEach { body ->
            mergeJsonElement(target, body.key, body.value)
        }
    }

    private fun mergeJsonElement(target: JSONObject, key: String, value: JsonElement) {
        when (value) {
            is JsonNull -> target.put(key, JSONObject.NULL)
            is JsonPrimitive -> target.put(key, value.toJsonValue())
            is JsonObject -> {
                val existing = target.optJSONObject(key)
                if (existing != null) {
                    value.entries.forEach { (childKey, childValue) ->
                        mergeJsonElement(existing, childKey, childValue)
                    }
                } else {
                    target.put(key, value.toJsonObject())
                }
            }
            is JsonArray -> target.put(key, value.toJsonArray())
        }
    }

    private fun JsonPrimitive.toJsonValue(): Any? = when {
        this is JsonNull -> JSONObject.NULL
        booleanOrNull != null -> booleanOrNull!!
        intOrNull != null -> intOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }

    private fun JsonObject.toJsonObject(): JSONObject = JSONObject().also { json ->
        entries.forEach { (key, value) ->
            when (value) {
                is JsonNull -> json.put(key, JSONObject.NULL)
                is JsonPrimitive -> json.put(key, value.toJsonValue())
                is JsonObject -> json.put(key, value.toJsonObject())
                is JsonArray -> json.put(key, value.toJsonArray())
            }
        }
    }

    private fun JsonArray.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { element ->
            when (element) {
                is JsonNull -> array.put(JSONObject.NULL)
                is JsonPrimitive -> array.put(element.toJsonValue())
                is JsonObject -> array.put(element.toJsonObject())
                is JsonArray -> array.put(element.toJsonArray())
            }
        }
    }
}
