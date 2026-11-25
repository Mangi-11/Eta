package io.github.mangi.eta.agent.model.catalog

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.device.SystemStateControls
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** 同一份领域目录用于模型、权限元数据和工具页，避免新增入口时漏接一端。 */
internal object AgentPhoneToolCatalog {
    data class Entry(
        val name: String,
        @StringRes private val titleRes: Int,
        private val titleFallback: String,
        val description: String,
        @StringRes private val descriptionRes: Int,
        val write: Boolean,
        val root: RootRequirement,
        val colorOs: Boolean = false,
        val personal: Boolean = false,
        val direct: Boolean = false,
    ) {
        val title: String
            get() = AppStrings.get(titleRes, titleFallback)

        fun localizedDescription(): String = AppStrings.get(descriptionRes, description)
    }

    val entries =
        listOf(
            Entry(
                name = "get_flashlight",
                titleRes = R.string.tool_cat_get_flashlight_title,
                titleFallback = "手电筒状态",
                description = "查询手电筒实际状态及是否被相机占用。",
                descriptionRes = R.string.tool_cat_get_flashlight_desc,
                write = false,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "set_flashlight",
                titleRes = R.string.tool_cat_set_flashlight_title,
                titleFallback = "设置手电筒",
                description = "开启或关闭手电筒并通过系统回调验证；已处于目标状态时不重复切换。",
                descriptionRes = R.string.tool_cat_set_flashlight_desc,
                write = true,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "get_device_state",
                titleRes = R.string.tool_cat_get_device_state_title,
                titleFallback = "查询系统开关",
                description = "查询指定系统开关，不用 GUI。",
                descriptionRes = R.string.tool_cat_get_device_state_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "get_display_state",
                titleRes = R.string.tool_cat_get_display_state_title,
                titleFallback = "显示状态",
                description = "查询亮度、自动亮度、旋转和息屏时间。",
                descriptionRes = R.string.tool_cat_get_display_state_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_brightness",
                titleRes = R.string.tool_cat_set_brightness_title,
                titleFallback = "设置亮度",
                description = "按系统亮度滑块百分比设置并读回验证；不支持可靠百分比接口时明确失败。",
                descriptionRes = R.string.tool_cat_set_brightness_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_screen_timeout",
                titleRes = R.string.tool_cat_set_screen_timeout_title,
                titleFallback = "设置息屏时间",
                description = "设置无操作后的自动息屏秒数，并读回验证。",
                descriptionRes = R.string.tool_cat_set_screen_timeout_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "get_sound_state",
                titleRes = R.string.tool_cat_get_sound_state_title,
                titleFallback = "声音状态",
                description = "读取各类音量、响铃及勿扰状态。",
                descriptionRes = R.string.tool_cat_get_sound_state_desc,
                write = false,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "set_sound_mode",
                titleRes = R.string.tool_cat_set_sound_mode_title,
                titleFallback = "设置响铃模式",
                description = "设置正常、静音或振动模式并验证；普通身份可能需要勿扰访问权限。",
                descriptionRes = R.string.tool_cat_set_sound_mode_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "set_do_not_disturb",
                titleRes = R.string.tool_cat_set_do_not_disturb_title,
                titleFallback = "设置勿扰模式",
                description = "直接设置勿扰策略并读回验证。",
                descriptionRes = R.string.tool_cat_set_do_not_disturb_desc,
                write = true,
                root = RootRequirement.REQUIRED,
            ),
            Entry(
                name = "get_hotspot",
                titleRes = R.string.tool_cat_get_hotspot_title,
                titleFallback = "热点状态",
                description = "查询互联网共享热点是否开启；需要系统提供兼容接口。",
                descriptionRes = R.string.tool_cat_get_hotspot_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_hotspot",
                titleRes = R.string.tool_cat_set_hotspot_title,
                titleFallback = "设置个人热点",
                description = "使用现有热点配置开启或关闭网络共享，不更改名称和密码。",
                descriptionRes = R.string.tool_cat_set_hotspot_desc,
                write = true,
                root = RootRequirement.REQUIRED,
            ),
            Entry(
                name = "list_calendars",
                titleRes = R.string.tool_cat_list_calendars_title,
                titleFallback = "可用日历",
                description = "列出当前用户可写的日历 ID 和名称；创建时必须选择真实日历，不能猜 calendar_id。",
                descriptionRes = R.string.tool_cat_list_calendars_desc,
                write = false,
                root = RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                name = "read_calendar_event",
                titleRes = R.string.tool_cat_read_calendar_event_title,
                titleFallback = "日程详情",
                description = "按原始 event_id 读取实时事件与提醒；不得使用 DMP 文档 ID。",
                descriptionRes = R.string.tool_cat_read_calendar_event_desc,
                write = false,
                root = RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                name = "create_calendar_event",
                titleRes = R.string.tool_cat_create_calendar_event_title,
                titleFallback = "创建日程",
                description = "直接创建日程和提醒，事务完成后读回确认。一次性日程使用起止时间；全天事件使用 UTC 零点和排他的结束零点。超时后先查询，不要重复创建。",
                descriptionRes = R.string.tool_cat_create_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "create_calendar_events",
                titleRes = R.string.tool_cat_create_calendar_events_title,
                titleFallback = "批量创建日程",
                description = "一次创建同一日历中的多条日程和提醒；先校验全部参数，再在同一事务中写入并逐条核对。最多 30 条，结果未确认时先查询，不要重复创建。",
                descriptionRes = R.string.tool_cat_create_calendar_events_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "update_calendar_event",
                titleRes = R.string.tool_cat_update_calendar_event_title,
                titleFallback = "修改日程",
                description = "按原始 event_id 修改日程字段和提醒，并读回确认；修改时间须同时给出起止时间。当前不修改重复事件。",
                descriptionRes = R.string.tool_cat_update_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "delete_calendar_event",
                titleRes = R.string.tool_cat_delete_calendar_event_title,
                titleFallback = "删除日程",
                description = "删除精确原始 event_id；重复事件必须明确 scope=series，不能把索引 ID 当作事件 ID。",
                descriptionRes = R.string.tool_cat_delete_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "update_alarm_time",
                titleRes = R.string.tool_cat_update_alarm_time_title,
                titleFallback = "修改闹钟时间",
                description = "按 alarm_id 修改小时和分钟，保留原启用状态并验证。",
                descriptionRes = R.string.tool_cat_update_alarm_time_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "set_alarm_enabled",
                titleRes = R.string.tool_cat_set_alarm_enabled_title,
                titleFallback = "闹钟开关",
                description = "启用或关闭精确 alarm_id，读回验证；不按模糊时间匹配多个闹钟。",
                descriptionRes = R.string.tool_cat_set_alarm_enabled_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "delete_alarm",
                titleRes = R.string.tool_cat_delete_alarm_title,
                titleFallback = "删除闹钟",
                description = "删除精确 alarm_id 后核对；不得使用 DMP 索引 ID。",
                descriptionRes = R.string.tool_cat_delete_alarm_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "create_note",
                titleRes = R.string.tool_cat_create_note_title,
                titleFallback = "创建便签",
                description = "直接创建系统便签，解析实际结果 UUID 并读回；返回未确认时不能重复创建。",
                descriptionRes = R.string.tool_cat_create_note_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "read_note",
                titleRes = R.string.tool_cat_read_note_title,
                titleFallback = "便签详情",
                description = "按原始 note_id UUID 读取便签正文；不能使用 DMP 文档 ID。",
                descriptionRes = R.string.tool_cat_read_note_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                colorOs = true,
                personal = true,
            ),
            Entry(
                name = "delete_note",
                titleRes = R.string.tool_cat_delete_note_title,
                titleFallback = "回收便签",
                description = "按原始 note_id UUID 将便签移入回收站并验证，不是永久删除。",
                descriptionRes = R.string.tool_cat_delete_note_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
        )
    val names = entries.mapTo(linkedSetOf()) { it.name }
    val direct = entries.filter { it.direct }.mapTo(linkedSetOf()) { it.name }
    val reads = entries.filter { !it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }
    val writes = entries.filter { it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }

    fun appendTo(tools: JSONArray, direct: Boolean, reads: Boolean, writes: Boolean) {
        entries
            .filter { if (it.direct) direct else if (it.write) writes else reads }
            .forEach { tools.put(schema(it)) }
    }

    private fun schema(entry: Entry): JSONObject {
        val p = JSONObject()
        val required = mutableListOf<String>()
        fun field(key: String, schema: JSONObject, need: Boolean = true) {
            p.put(key, schema)
            if (need) required += key
        }
        fun id(key: String) = field(key, text("原始对象 ID", 36))
        when (entry.name) {
            "set_flashlight",
            "set_hotspot" -> field("enabled", JSONObject().put("type", "boolean"))
            "get_device_state" -> field("target", enum(SystemStateControls.targets))
            "set_brightness" -> field("percent", integer(0, 100))
            "set_screen_timeout" -> field("seconds", integer(15, 1800))
            "set_sound_mode" -> field("mode", enum(listOf("normal", "silent", "vibrate")))
            "set_do_not_disturb" ->
                field("mode", enum(listOf("off", "priority", "alarms", "silent")))
            "read_calendar_event" -> id("event_id")
            "create_calendar_events" -> {
                field("calendar_id", text("可写日历 ID", 19), false)
                val single =
                    schema(entries.first { it.name == "create_calendar_event" })
                        .getJSONObject("function")
                        .getJSONObject("parameters")
                single.getJSONObject("properties").remove("calendar_id")
                field(
                    "events",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 30)
                        .put("items", single),
                )
            }
            "create_calendar_event",
            "update_calendar_event" -> {
                val create = entry.name == "create_calendar_event"
                if (create)
                    field("calendar_id", text("list_calendars 返回的可写日历 ID；仅一个日历时可省略", 19), false)
                else id("event_id")
                field("title", text("日程标题", 200), create)
                field("start_time", text("带偏移的 ISO 8601 起始时间", 64), create)
                field("end_time", text("带偏移的 ISO 8601 结束时间", 64), create)
                field("timezone", text("IANA 时区，如 Asia/Shanghai", 80), false)
                field("all_day", JSONObject().put("type", "boolean"), false)
                field("location", text("地点", 500), false)
                field("description", text("说明", 8000), false)
                field(
                    "reminder_minutes",
                    JSONObject()
                        .put("type", "array")
                        .put("items", integer(0, 40320))
                        .put("maxItems", 5),
                    false,
                )
            }
            "delete_calendar_event" -> {
                id("event_id")
                field("scope", enum(listOf("event", "series")), false)
            }
            "update_alarm_time" -> {
                id("alarm_id")
                field("hour", integer(0, 23))
                field("minute", integer(0, 59))
            }
            "set_alarm_enabled" -> {
                id("alarm_id")
                field("enabled", JSONObject().put("type", "boolean"))
            }
            "delete_alarm" -> id("alarm_id")
            "create_note" -> {
                field("title", text("标题", 200), false)
                field("content", text("便签正文", 20000))
            }
            "read_note",
            "delete_note" -> id("note_id")
        }
        return AgentPersonalSearchToolCatalog.function(
            entry.name,
            entry.description,
            p,
            *required.toTypedArray(),
        )
    }

    private fun text(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)

    private fun integer(min: Int, max: Int) =
        JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)

    private fun enum(values: List<String>) =
        JSONObject().put("type", "string").put("enum", JSONArray(values))
}
