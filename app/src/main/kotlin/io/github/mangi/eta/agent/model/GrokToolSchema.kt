package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 订阅接口独立编译根联合的对象分支；仅展开依赖外层字段的 required 分支。 */
internal object GrokToolSchema {
    fun project(parameters: JSONObject): JSONObject {
        val result = JSONObject(parameters.toString())
        if (result.optString("type") != "object" || hasReferences(result)) return result
        val union = listOf("oneOf", "anyOf").singleOrNull(result::has) ?: return result
        val branches = result.optJSONArray(union) ?: return result
        if (branches.length() == 0) return result
        val requirements = (0 until branches.length()).map { index ->
            val branch = branches.optJSONObject(index) ?: return result
            if (branch.length() != 1) return result
            val required = branch.optJSONArray("required") ?: return result
            if ((0 until required.length()).any { required.opt(it) !is String }) return result
            required
        }
        val common = JSONObject(result.toString()).apply { remove(union) }
        val commonRequired = common.optJSONArray("required") ?: JSONArray()
        result.put(union, JSONArray().also { expanded ->
            for (required in requirements) {
                val names = linkedSetOf<String>()
                for (array in listOf(commonRequired, required)) {
                    for (index in 0 until array.length()) names += array.getString(index)
                }
                expanded.put(JSONObject(common.toString()).put("required", JSONArray(names.toList())))
            }
        })
        return result
    }

    // 引用或锚点的复制可能改变解析作用域，保留原样交给服务端解释。
    private fun hasReferences(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            key.startsWith("$") || key == "id" || hasReferences(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).any { hasReferences(value.opt(it)) }
        else -> false
    }
}
