package io.github.mangi.eta.agent.model

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** 在工具执行前校验模型参数；这里只检查调用合同，不承担权限审批或安全策略。 */
internal class AgentToolCallValidator(tools: JSONArray) {
    private data class ToolSchema(
        val parameters: JSONObject,
        val root: JSONObject,
    )

    private val schemasByName: Map<String, ToolSchema> = buildMap {
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = function.optString("name").trim()
            val parameters = function.optJSONObject("parameters") ?: continue
            if (name.isNotBlank()) put(name, ToolSchema(parameters, parameters))
        }
    }

    fun validate(call: AgentModelClient.ToolCall): String? {
        val toolSchema = schemasByName[call.name]
            ?: return "工具未在本次运行的能力目录中声明"
        val arguments = parseArguments(call.argumentsJson)
            ?: return "参数不是有效的 JSON object"
        // 兼容少数模型把参数包进 arguments/args/parameters 一层壳（或字符串化）的输出：
        // 外层校验失败时，再用解壳后的对象校验一次，避免误报"缺少必填字段"。
        val candidates = listOfNotNull(arguments, unwrapArgumentWrapper(arguments))
        var firstError: String? = null
        for (candidate in candidates) {
            val error = validateValue(
                value = candidate,
                schema = toolSchema.parameters,
                root = toolSchema.root,
                path = "arguments",
                depth = 0,
            )
            if (error == null) return null
            if (firstError == null) firstError = error
        }
        val received = arguments.keys().asSequence().joinToString(", ").ifBlank { "（空对象）" }
        return "$firstError（实际收到字段: $received；tool_call id: ${call.id}）"
    }

    /** 容错解析：整体可能是对象，也可能是被字符串化的一层对象。 */
    private fun parseArguments(raw: String): JSONObject? {
        val text = raw.trim().ifBlank { "{}" }
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        val inner = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull()
        return when (inner) {
            is JSONObject -> inner
            is String -> runCatching { JSONObject(inner) }.getOrNull()
            else -> null
        }
    }

    /** 仅当外层只有一个 arguments/args/parameters 键且值可解出对象时，返回解壳候选。 */
    private fun unwrapArgumentWrapper(arguments: JSONObject): JSONObject? {
        if (arguments.length() != 1) return null
        val key = listOf("arguments", "args", "parameters").firstOrNull { arguments.has(it) } ?: return null
        return when (val inner = arguments.opt(key)) {
            is JSONObject -> inner
            is String -> runCatching { JSONObject(inner) }.getOrNull()
            else -> null
        }
    }

    private fun validateValue(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        if (depth > MAX_SCHEMA_DEPTH) return "$path 的 Schema 引用层级过深"

        schema.optString("${'$'}ref").takeIf { it.isNotBlank() }?.let { reference ->
            val referenced = resolveReference(root, reference)
                ?: return "$path 的 Schema 引用无法解析：$reference"
            validateSchema(value, referenced, root, path, depth + 1)?.let { return it }
        }

        validateComposition(value, schema, root, path, depth)?.let { return it }

        if (schema.optBoolean("nullable", false) && isJsonNull(value)) return null
        val type = schema.opt("type")
        if (type != null && type != JSONObject.NULL && !matchesType(value, type)) {
            return "$path 类型应为 ${describeType(type)}"
        }

        if (schema.has("const") && !jsonEquals(schema.opt("const"), value)) {
            return "$path 必须等于 Schema 声明的固定值"
        }
        val enum = schema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).none { jsonEquals(enum.opt(it), value) }) {
            return "$path 不在允许值集合中"
        }

        return when (value) {
            is JSONObject -> validateObject(value, schema, root, path, depth)
            is JSONArray -> validateArray(value, schema, root, path, depth)
            is String -> validateString(value, schema, path)
            is Number -> validateNumber(value, schema, path)
            else -> null
        }
    }

    private fun validateComposition(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        schema.optJSONArray("allOf")?.let { branches ->
            for (index in 0 until branches.length()) {
                validateSchema(value, branches.opt(index), root, path, depth + 1)?.let { return it }
            }
        }
        schema.optJSONArray("anyOf")?.let { branches ->
            if (!matchesBranchCount(value, branches, root, path, depth, minimum = 1)) {
                return "$path 不符合 anyOf 中的任何 Schema"
            }
        }
        schema.optJSONArray("oneOf")?.let { branches ->
            if (!matchesBranchCount(value, branches, root, path, depth, minimum = 1, maximum = 1)) {
                return "$path 必须且只能符合 oneOf 中的一个 Schema"
            }
        }
        schema.opt("not").takeUnless { it == null || it == JSONObject.NULL }?.let { rejected ->
            if (validateSchema(value, rejected, root, path, depth + 1) == null) {
                return "$path 符合了 not 禁止的 Schema"
            }
        }
        schema.opt("if").takeUnless { it == null || it == JSONObject.NULL }?.let { condition ->
            val branch = if (validateSchema(value, condition, root, path, depth + 1) == null) {
                schema.opt("then")
            } else {
                schema.opt("else")
            }
            if (branch != null && branch != JSONObject.NULL) {
                validateSchema(value, branch, root, path, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun matchesBranchCount(
        value: Any?,
        branches: JSONArray,
        root: JSONObject,
        path: String,
        depth: Int,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE,
    ): Boolean {
        var matches = 0
        for (index in 0 until branches.length()) {
            if (validateSchema(value, branches.opt(index), root, path, depth + 1) == null) matches += 1
            if (matches > maximum) return false
        }
        return matches in minimum..maximum
    }

    private fun validateObject(
        value: JSONObject,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        val size = value.length()
        schema.optInteger("minProperties")?.let { if (size < it) return "$path 的字段数不能少于 $it" }
        schema.optInteger("maxProperties")?.let { if (size > it) return "$path 的字段数不能超过 $it" }

        schema.optJSONArray("required")?.let { required ->
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                if (!value.has(key)) return "$path 缺少必填字段 $key"
            }
        }

        schema.optJSONObject("dependentRequired")?.let { dependencies ->
            for (key in dependencies.keys()) {
                if (!value.has(key)) continue
                val required = dependencies.optJSONArray(key) ?: continue
                for (index in 0 until required.length()) {
                    val dependent = required.optString(index)
                    if (!value.has(dependent)) return "$path.$key 要求同时提供字段 $dependent"
                }
            }
        }

        val properties = schema.optJSONObject("properties")
        val patternProperties = schema.optJSONObject("patternProperties")
        val additionalProperties = schema.opt("additionalProperties")
        for (key in value.keys()) {
            val childPath = "$path.$key"
            val childValue = value.opt(key)
            var matched = false
            properties?.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { childSchema ->
                matched = true
                validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
            }
            if (patternProperties != null) {
                for (pattern in patternProperties.keys()) {
                    val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
                    if (!regex.containsMatchIn(key)) continue
                    matched = true
                    val childSchema = patternProperties.opt(pattern) ?: continue
                    validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
                }
            }
            if (!matched) {
                when (additionalProperties) {
                    false -> return "$path 不允许额外字段 $key"
                    is JSONObject, is Boolean ->
                        validateSchema(childValue, additionalProperties, root, childPath, depth + 1)?.let { return it }
                }
            }
        }

        schema.opt("propertyNames").takeUnless { it == null || it == JSONObject.NULL }?.let { nameSchema ->
            for (key in value.keys()) {
                validateSchema(key, nameSchema, root, "$path 的字段名 $key", depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun validateArray(
        value: JSONArray,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        schema.optInteger("minItems")?.let { if (value.length() < it) return "$path 项目数不能少于 $it" }
        schema.optInteger("maxItems")?.let { if (value.length() > it) return "$path 项目数不能超过 $it" }
        if (schema.optBoolean("uniqueItems", false)) {
            for (left in 0 until value.length()) {
                for (right in left + 1 until value.length()) {
                    if (jsonEquals(value.opt(left), value.opt(right))) return "$path 不允许重复项目"
                }
            }
        }

        val prefixItems = schema.optJSONArray("prefixItems")
        if (prefixItems != null) {
            for (index in 0 until minOf(prefixItems.length(), value.length())) {
                validateSchema(value.opt(index), prefixItems.opt(index), root, "$path[$index]", depth + 1)
                    ?.let { return it }
            }
        }
        when (val items = schema.opt("items")) {
            is JSONObject -> {
                val start = prefixItems?.length() ?: 0
                for (index in start until value.length()) {
                    validateValue(value.opt(index), items, root, "$path[$index]", depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (index in 0 until minOf(items.length(), value.length())) {
                    validateSchema(value.opt(index), items.opt(index), root, "$path[$index]", depth + 1)
                        ?.let { return it }
                }
            }
            false -> if (value.length() > (prefixItems?.length() ?: 0)) return "$path 不允许更多项目"
        }

        schema.opt("contains").takeUnless { it == null || it == JSONObject.NULL }?.let { contains ->
            val matches = (0 until value.length()).count { index ->
                validateSchema(value.opt(index), contains, root, "$path[$index]", depth + 1) == null
            }
            val minimum = schema.optInteger("minContains") ?: 1
            val maximum = schema.optInteger("maxContains") ?: Int.MAX_VALUE
            if (matches !in minimum..maximum) return "$path 中符合 contains 的项目数必须在 $minimum..$maximum 之间"
        }
        return null
    }

    private fun validateString(value: String, schema: JSONObject, path: String): String? {
        schema.optInteger("minLength")?.let { if (value.codePointCount(0, value.length) < it) return "$path 长度不能少于 $it" }
        schema.optInteger("maxLength")?.let { if (value.codePointCount(0, value.length) > it) return "$path 长度不能超过 $it" }
        schema.optString("pattern").takeIf { it.isNotBlank() }?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
                ?: return "$path 的 Schema pattern 无效"
            if (!regex.containsMatchIn(value)) return "$path 不符合 pattern $pattern"
        }
        return null
    }

    private fun validateNumber(value: Number, schema: JSONObject, path: String): String? {
        val number = value.toBigDecimal() ?: return "$path 不是有效数字"
        schema.optBigDecimal("minimum")?.let { if (number < it) return "$path 不能小于 $it" }
        schema.optBigDecimal("maximum")?.let { if (number > it) return "$path 不能大于 $it" }
        schema.optBigDecimal("exclusiveMinimum")?.let { if (number <= it) return "$path 必须大于 $it" }
        schema.optBigDecimal("exclusiveMaximum")?.let { if (number >= it) return "$path 必须小于 $it" }
        schema.optBigDecimal("multipleOf")?.takeIf { it.signum() != 0 }?.let { divisor ->
            if (number.remainder(divisor).compareTo(BigDecimal.ZERO) != 0) return "$path 必须是 $divisor 的倍数"
        }
        return null
    }

    private fun matchesType(value: Any?, declared: Any): Boolean {
        if (declared is JSONArray) {
            return (0 until declared.length()).any { matchesType(value, declared.optString(it)) }
        }
        val type = declared as? String ?: return true
        return when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number
            "integer" -> value is Number && value.toBigDecimal()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
            "null" -> isJsonNull(value)
            else -> true
        }
    }

    private fun validateSchema(
        value: Any?,
        schema: Any?,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? = when (schema) {
        true -> null
        false -> "$path 被 false Schema 拒绝"
        is JSONObject -> validateValue(value, schema, root, path, depth)
        else -> "$path 的 Schema 节点无效"
    }

    private fun resolveReference(root: JSONObject, reference: String): Any? {
        if (!reference.startsWith("#")) return null
        if (reference == "#") return root
        if (!reference.startsWith("#/")) return null
        var current: Any? = root
        for (rawToken in reference.removePrefix("#/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JSONObject -> if (current.has(token)) current.opt(token) else return null
                is JSONArray -> token.toIntOrNull()?.let { current.opt(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = canonicalJson(left) == canonicalJson(right)

    private fun canonicalJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonicalJson(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { canonicalJson(value.opt(it)) }
        is Number -> value.toBigDecimal()?.stripTrailingZeros()
        else -> value
    }

    private fun Number.toBigDecimal(): BigDecimal? = runCatching { BigDecimal(toString()) }.getOrNull()

    private fun JSONObject.optInteger(name: String): Int? =
        opt(name).takeIf { it is Number }?.let { (it as Number).toInt() }

    private fun JSONObject.optBigDecimal(name: String): BigDecimal? =
        (opt(name) as? Number)?.toBigDecimal()

    private fun describeType(type: Any): String = when (type) {
        is JSONArray -> (0 until type.length()).joinToString(" 或 ") { type.optString(it) }
        else -> type.toString()
    }

    private fun isJsonNull(value: Any?): Boolean = value == null || value == JSONObject.NULL

    private companion object {
        const val MAX_SCHEMA_DEPTH = 256
    }
}
