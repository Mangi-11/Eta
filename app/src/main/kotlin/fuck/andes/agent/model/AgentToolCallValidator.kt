package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 在任何设备副作用发生前，按发送给模型的同一份 schema 校验工具参数。 */
internal class AgentToolCallValidator(tools: JSONArray) {
    private val parametersByName: Map<String, JSONObject> = buildMap {
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = function.optString("name").trim()
            val parameters = function.optJSONObject("parameters") ?: continue
            if (name.isNotBlank()) put(name, parameters)
        }
    }

    fun validate(call: AgentModelClient.ToolCall): String? {
        val schema = parametersByName[call.name]
            ?: return "工具未在本次运行的能力目录中声明"
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return "参数不是有效的 JSON object" }
        return validateValue(arguments, schema, path = "arguments")
    }

    private fun validateValue(value: Any?, schema: JSONObject, path: String): String? {
        val type = schema.optString("type")
        if (type.isNotBlank() && !matchesType(value, type)) {
            return "$path 类型应为 $type"
        }

        val enum = schema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).none { enum.opt(it) == value }) {
            return "$path 不在允许值集合中"
        }

        return when (value) {
            is JSONObject -> validateObject(value, schema, path)
            is JSONArray -> validateArray(value, schema, path)
            is String -> validateString(value, schema, path)
            is Number -> validateNumber(value, schema, path)
            else -> null
        }
    }

    private fun validateObject(value: JSONObject, schema: JSONObject, path: String): String? {
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                if (!value.has(key) || value.isNull(key)) return "$path 缺少必填字段 $key"
            }
        }

        val properties = schema.optJSONObject("properties") ?: return null
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val childSchema = properties.optJSONObject(key) ?: continue
            validateValue(value.opt(key), childSchema, "$path.$key")?.let { return it }
        }
        return null
    }

    private fun validateArray(value: JSONArray, schema: JSONObject, path: String): String? {
        val minItems = schema.optInt("minItems", -1)
        if (minItems >= 0 && value.length() < minItems) return "$path 项目数不能少于 $minItems"
        val maxItems = schema.optInt("maxItems", -1)
        if (maxItems >= 0 && value.length() > maxItems) return "$path 项目数不能超过 $maxItems"
        val itemSchema = schema.optJSONObject("items") ?: return null
        for (index in 0 until value.length()) {
            validateValue(value.opt(index), itemSchema, "$path[$index]")?.let { return it }
        }
        return null
    }

    private fun validateString(value: String, schema: JSONObject, path: String): String? {
        val minLength = schema.optInt("minLength", -1)
        if (minLength >= 0 && value.length < minLength) return "$path 长度不能少于 $minLength"
        val maxLength = schema.optInt("maxLength", -1)
        if (maxLength >= 0 && value.length > maxLength) return "$path 长度不能超过 $maxLength"
        return null
    }

    private fun validateNumber(value: Number, schema: JSONObject, path: String): String? {
        val number = value.toDouble()
        if (schema.has("minimum") && number < schema.optDouble("minimum")) {
            return "$path 不能小于 ${schema.opt("minimum")}"
        }
        if (schema.has("maximum") && number > schema.optDouble("maximum")) {
            return "$path 不能大于 ${schema.opt("maximum")}"
        }
        return null
    }

    private fun matchesType(value: Any?, type: String): Boolean =
        when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number
            "integer" -> value is Byte || value is Short || value is Int || value is Long
            "null" -> value == null || value == JSONObject.NULL
            else -> true
        }
}
