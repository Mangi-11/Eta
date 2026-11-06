package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentPhoneResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") == "unconfirmed") return null
        when (toolName) {
            "list_calendars" -> return calendarCount(json)
            "list_alarms" -> return alarmCount(json)
            "list_active_timers" -> return timerCount(json)
            "read_note" -> return json.optJSONObject("note")?.let { "已读取便签" }
            "read_calendar_event" -> return json.optJSONObject("event")?.let { "已读取日程" }
        }
        if (json.optString("status") == "dispatched") {
            return when (toolName) {
                "set_alarm" -> withDetail("已提交闹钟请求", alarmTime(json))
                "set_timer" -> withDetail("已提交计时请求", timerDuration(json))
                else -> null
            }
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_alarm" -> withDetail("已创建闹钟", alarmTime(json.optJSONObject("alarm")))
            "set_timer" -> withDetail("已设置计时器", timerDuration(json.optJSONObject("timer") ?: json))
            "update_alarm_time" -> alarmTimeChange(json)
            "set_alarm_enabled" -> alarmEnabled(json)
            "delete_alarm" -> withDetail("已删除闹钟", alarmTime(json.optJSONObject("before")))
            "create_calendar_event", "create_calendar_events" -> {
                val count = json.integer("count", 1, 30)
                if (count != null) "已创建 $count 个日程" else "已创建日程"
            }
            "update_calendar_event" -> calendarChange(json)
            "delete_calendar_event" -> "已删除日程"
            "create_note" -> "已创建便签"
            "delete_note" -> if (json.optString("mode") == "recycle") "便签已移至回收站" else null
            else -> null
        }
    }

    private fun calendarCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return "未找到可用日历"
        return "找到 $count 个可用日历" + if (json.opt("has_more") == true) " · 还有更多" else ""
    }

    private fun alarmCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return "暂无闹钟"
        return "找到 $count 个闹钟" + if (json.opt("has_more") == true) " · 还有更多" else ""
    }

    private fun timerCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return "暂无活跃计时器"
        return "找到 $count 个计时器" + if (json.opt("has_more") == true) " · 还有更多" else ""
    }

    private fun alarmTime(json: JSONObject?): String? {
        val hour = json?.integer("hour", 0, 23) ?: return null
        val minute = json.integer("minute", 0, 59) ?: return null
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun timerDuration(json: JSONObject): String? {
        val seconds = json.integer("duration_seconds", 1, 86400) ?: return null
        return buildList {
            if (seconds >= 3600) add("${seconds / 3600} 小时")
            if (seconds % 3600 >= 60) add("${seconds % 3600 / 60} 分钟")
            if (seconds % 60 > 0) add("${seconds % 60} 秒")
        }.joinToString(" ")
    }

    private fun alarmTimeChange(json: JSONObject): String {
        val before = alarmTime(json.optJSONObject("before"))
        val alarm = json.optJSONObject("alarm")
        val after = alarmTime(alarm)
        val summary = when {
            before != null && after != null && before == after -> "闹钟时间未变化 · $after"
            before != null && after != null -> "闹钟时间：$before → $after"
            else -> withDetail("已更新闹钟时间", after)
        }
        return summary + if (alarm?.opt("enabled") == false) " · 未启用" else ""
    }

    private fun alarmEnabled(json: JSONObject): String? {
        val alarm = json.optJSONObject("alarm") ?: return null
        val enabled = alarm.opt("enabled") as? Boolean ?: return null
        val before = json.optJSONObject("before")?.opt("enabled") as? Boolean
        val state = if (enabled) "开启" else "关闭"
        val summary = if (before == enabled) "闹钟保持$state" else "闹钟已$state"
        return withDetail(summary, alarmTime(alarm))
    }

    private fun calendarChange(json: JSONObject): String {
        val before = json.optJSONObject("before") ?: return "已更新日程"
        val after = json.optJSONObject("event") ?: return "已更新日程"
        val fields = listOf(
            "标题" to listOf("title"),
            "时间" to listOf("dtstart", "dtend", "eventTimezone", "allDay"),
            "地点" to listOf("eventLocation"),
            "说明" to listOf("description"),
            "提醒" to listOf("reminders"),
        )
        val changes = fields.mapNotNull { (label, keys) ->
            label.takeIf {
                keys.any { key ->
                    before.has(key) && after.has(key) &&
                        calendarValue(before, key) != calendarValue(after, key)
                }
            }
        }
        if (changes.isNotEmpty()) return "已更新日程 · ${changes.joinToString("、")}"
        val keys = fields.flatMap { it.second }
        val complete = keys.all { hasComparableCalendarValue(before, it) && hasComparableCalendarValue(after, it) }
        return if (complete) "日程未变化" else "已更新日程"
    }

    private fun hasComparableCalendarValue(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return false
        val value = json.opt(key)
        if (key != "reminders") return value == JSONObject.NULL || value is String || value is Number
        if (value !is JSONArray) return false
        return (0 until value.length()).all { index ->
            val reminder = value.optJSONObject(index)
            reminder != null && listOf("minutes", "method").all {
                reminder.opt(it) is String || reminder.opt(it) is Number
            }
        }
    }

    private fun calendarValue(json: JSONObject, key: String): Any? {
        val value = json.opt(key)
        if (key != "reminders" || value !is JSONArray) return value
        // 提醒顺序不影响效果，只比较分钟数与提醒方式，忽略 Provider 返回的记录标识。
        return (0 until value.length()).map { index ->
            value.optJSONObject(index)?.let { reminder ->
                "${reminder.opt("minutes")}:${reminder.opt("method")}"
            }
        }.sortedBy { it }
    }

    private fun JSONObject.integer(key: String, minimum: Int, maximum: Int): Int? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()
            ?.takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()

    private fun withDetail(summary: String, detail: String?): String =
        if (detail == null) summary else "$summary · $detail"
}
