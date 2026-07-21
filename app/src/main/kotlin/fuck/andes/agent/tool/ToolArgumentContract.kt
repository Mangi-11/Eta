package fuck.andes.agent.tool

import org.json.JSONObject

/**
 * 工具 schema 只约束模型输出；执行边界仍需拒绝缺失或类型错误的副作用参数，
 * 避免 `optInt`/`optString` 把畸形调用静默变成坐标 0 或空文本。
 */
internal object ToolArgumentContract {
    data class Issue(val field: String, val message: String)

    private enum class Kind(val label: String) {
        STRING("string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
    }

    private data class Field(
        val name: String,
        val kind: Kind,
        val required: Boolean = false,
        val values: Set<String> = emptySet(),
        val minimum: Int? = null,
        val maximum: Int? = null,
    )

    private val contracts = mapOf(
        "observe_screen" to listOf(
            Field("include_screenshot", Kind.BOOLEAN),
            Field("include_ui_tree", Kind.BOOLEAN),
            Field("max_nodes", Kind.INTEGER, minimum = 1, maximum = 120),
        ),
        "tap" to coordinateFields("x", "y"),
        "tap_area" to coordinateFields("x1", "y1", "x2", "y2"),
        "tap_element" to elementFields(),
        "long_press" to coordinateFields("x", "y") +
            Field("duration_ms", Kind.INTEGER, minimum = 300, maximum = 3_000),
        "long_press_element" to elementFields() +
            Field("duration_ms", Kind.INTEGER, minimum = 300, maximum = 3_000),
        "swipe" to coordinateFields("x1", "y1", "x2", "y2") +
            Field("duration_ms", Kind.INTEGER, minimum = 100, maximum = 2_000),
        "scroll" to listOf(directionField()),
        "scroll_element" to elementFields() + directionField(),
        "input_text" to listOf(
            Field("text", Kind.STRING, required = true),
            Field("mode", Kind.STRING, values = setOf("append", "replace", "paste")),
            Field("index", Kind.INTEGER, minimum = 0),
            Field("observation_id", Kind.STRING),
        ),
        "replace_text" to editableFields(includeText = true),
        "clear_text" to editableFields(includeText = false),
        "set_clipboard" to listOf(Field("text", Kind.STRING, required = true)),
        "paste_text" to listOf(Field("text", Kind.STRING, required = true)),
        "press_key" to listOf(
            Field(
                "button",
                Kind.STRING,
                required = true,
                values = setOf(
                    "back",
                    "home",
                    "enter",
                    "recents",
                    "paste",
                    "notifications",
                    "quick_settings",
                ),
            ),
        ),
        "wait" to listOf(Field("duration_ms", Kind.INTEGER, minimum = 100, maximum = 30_000)),
        "wait_for_text" to listOf(
            Field("text", Kind.STRING, required = true),
            Field("timeout_ms", Kind.INTEGER, minimum = 500, maximum = 60_000),
            Field("include_desc", Kind.BOOLEAN),
            Field("match", Kind.STRING, values = setOf("contains", "exact", "prefix", "regex")),
        ),
        "wait_for_package" to listOf(
            Field("package_name", Kind.STRING, required = true),
            Field("timeout_ms", Kind.INTEGER, minimum = 500, maximum = 60_000),
        ),
        "open_system_panel" to listOf(
            Field(
                "panel",
                Kind.STRING,
                required = true,
                values = setOf("notifications", "quick_settings"),
            ),
        ),
    )

    fun validate(toolName: String, args: JSONObject): Issue? {
        val fields = contracts[toolName] ?: return null
        for (field in fields) {
            if (!args.has(field.name) || args.isNull(field.name)) {
                if (field.required) {
                    return Issue(field.name, "缺少必填参数 ${field.name}")
                }
                continue
            }
            val value = args.opt(field.name)
            if (!matchesKind(value, field.kind)) {
                return Issue(field.name, "参数 ${field.name} 必须是 ${field.kind.label}")
            }
            if (field.kind == Kind.INTEGER) {
                val number = (value as Number).toLong()
                if (field.minimum != null && number < field.minimum) {
                    return Issue(field.name, "参数 ${field.name} 不能小于 ${field.minimum}")
                }
                if (field.maximum != null && number > field.maximum) {
                    return Issue(field.name, "参数 ${field.name} 不能大于 ${field.maximum}")
                }
            }
            if (
                field.values.isNotEmpty() &&
                (value as String).lowercase() !in field.values
            ) {
                return Issue(
                    field.name,
                    "参数 ${field.name} 仅支持 ${field.values.joinToString("/")}",
                )
            }
        }

        if (toolName in EDITABLE_TOOLS && args.has("index") && !args.isNull("index")) {
            if (!args.has("observation_id") || args.isNull("observation_id")) {
                return Issue("observation_id", "指定 index 时必须提供 observation_id")
            }
        }
        if (
            toolName == "input_text" &&
            args.has("index") &&
            !args.isNull("index") &&
            !args.optString("mode", "append").equals("replace", ignoreCase = true)
        ) {
            return Issue("index", "input_text 仅在 mode=replace 时支持 index")
        }
        if (toolName == "paste_text" && args.optString("text").isEmpty()) {
            return Issue("text", "paste_text 的 text 不能为空")
        }
        return null
    }

    private fun matchesKind(value: Any?, kind: Kind): Boolean = when (kind) {
        Kind.STRING -> value is String
        Kind.BOOLEAN -> value is Boolean
        Kind.INTEGER -> value is Number && value.toDouble().let { number ->
            number.isFinite() && number % 1.0 == 0.0 &&
                number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()
        }
    }

    private fun coordinateFields(vararg names: String): List<Field> =
        names.map { Field(it, Kind.INTEGER, required = true, minimum = 0) } +
            Field(
                "coordinate_space",
                Kind.STRING,
                values = setOf("screenshot", "screen"),
            )

    private fun elementFields(): List<Field> = listOf(
        Field("index", Kind.INTEGER, required = true, minimum = 0),
        Field("observation_id", Kind.STRING, required = true),
    )

    private fun editableFields(includeText: Boolean): List<Field> = buildList {
        if (includeText) add(Field("text", Kind.STRING, required = true))
        add(Field("index", Kind.INTEGER, minimum = 0))
        add(Field("observation_id", Kind.STRING))
    }

    private fun directionField(): Field = Field(
        "direction",
        Kind.STRING,
        required = true,
        values = setOf("up", "down", "left", "right"),
    )

    private val EDITABLE_TOOLS = setOf("input_text", "replace_text", "clear_text")
}
