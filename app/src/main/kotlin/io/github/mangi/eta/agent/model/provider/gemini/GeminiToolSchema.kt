package io.github.mangi.eta.agent.model.provider.gemini

import org.json.JSONArray
import org.json.JSONObject

/** Gemini 要求组合分支的 required 属性在该分支本地声明，原始工具合同仍由目录持有。 */
internal object GeminiToolSchema {
    private val combinators = setOf("allOf", "anyOf", "oneOf")
    private val schemaMaps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies")
    private val singleSchemas = setOf(
        "additionalProperties", "unevaluatedProperties", "propertyNames", "additionalItems", "unevaluatedItems",
        "contains", "contentSchema", "not", "if", "then", "else",
    )
    private val referenceKeywords = setOf(
        "\$id", "id", "\$anchor", "\$dynamicAnchor", "\$recursiveAnchor", "\$ref", "\$dynamicRef", "\$recursiveRef",
    )

    fun project(parameters: JSONObject): JSONObject = JSONObject(parameters.toString()).also {
        projectSchema(it, emptyMap())
    }

    private fun projectSchema(schema: JSONObject, inheritedProperties: Map<String, Any>) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        // 封闭规则可能观察子分支的属性评估结果；外层属性不能绕过本层继续传入后代。
        val canDeclare = listOf("additionalProperties", "unevaluatedProperties").none {
            schema.has(it) && schema.opt(it) != true
        }
        val inherited = if (!canDeclare || referenceKeywords.any(schema::has)) emptyMap() else inheritedProperties
        if (canDeclare) {
            val required = schema.optJSONArray("required")
            for (index in 0 until (required?.length() ?: 0)) {
                val name = required?.opt(index) as? String ?: continue
                val definition = inherited[name] ?: continue
                if (!properties.has(name) && canCopy(definition)) {
                    properties.put(name, if (definition is JSONObject) JSONObject(definition.toString()) else definition)
                    schema.put("properties", properties)
                }
            }
        }
        val visible = inherited.toMutableMap()
        for (name in properties.keys()) visible[name] = properties.get(name)
        forEachSubschema(schema) { child, sameInstance ->
            projectSchema(child, if (sameInstance) visible else emptyMap())
        }
    }

    private fun canCopy(definition: Any): Boolean {
        if (definition is Boolean) return true
        if (definition !is JSONObject || referenceKeywords.any(definition::has)) return false
        // 标识、锚点及引用保持原位置，避免复制后改变 URI 或动态引用作用域。
        var safe = true
        forEachSubschema(definition) { child, _ -> if (!canCopy(child)) safe = false }
        return safe
    }

    private fun forEachSubschema(schema: JSONObject, visit: (JSONObject, Boolean) -> Unit) {
        for (keyword in combinators) {
            schema.optJSONArray(keyword)?.let { children ->
                for (index in 0 until children.length()) children.optJSONObject(index)?.let { visit(it, true) }
            }
        }
        for (keyword in schemaMaps) {
            schema.optJSONObject(keyword)?.let { children ->
                for (name in children.keys()) children.optJSONObject(name)?.let { visit(it, false) }
            }
        }
        for (keyword in singleSchemas) schema.optJSONObject(keyword)?.let { visit(it, false) }
        for (keyword in listOf("items", "prefixItems")) {
            when (val children = schema.opt(keyword)) {
                is JSONObject -> visit(children, false)
                is JSONArray -> for (index in 0 until children.length()) {
                    children.optJSONObject(index)?.let { visit(it, false) }
                }
            }
        }
    }
}
