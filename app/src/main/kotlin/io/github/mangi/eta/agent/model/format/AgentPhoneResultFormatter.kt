package io.github.mangi.eta.agent.model.format

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPhoneResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") == "unconfirmed") return null
        when (toolName) {
            "list_calendars" -> return calendarCount(json)
            "list_alarms" -> return alarmCount(json)
            "list_active_timers" -> return timerCount(json)
            "read_note" -> return json.optJSONObject("note")?.let {
                AppStrings.get(R.string.trace_phone_note_read, "已读取便签")
            }
            "read_calendar_event" -> return json.optJSONObject("event")?.let {
                AppStrings.get(R.string.trace_phone_event_read, "已读取日程")
            }
        }
        if (json.optString("status") == "dispatched") {
            return when (toolName) {
                "set_alarm" -> withDetail(
                    AppStrings.get(R.string.trace_phone_alarm_submitted, "已提交闹钟请求"),
                    alarmTime(json),
                )
                "set_timer" -> withDetail(
                    AppStrings.get(R.string.trace_phone_timer_submitted, "已提交计时请求"),
                    timerDuration(json),
                )
                else -> null
            }
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_created, "已创建闹钟"),
                alarmTime(json.optJSONObject("alarm")),
            )
            "set_timer" -> withDetail(
                AppStrings.get(R.string.trace_phone_timer_set, "已设置计时器"),
                timerDuration(json.optJSONObject("timer") ?: json),
            )
            "update_alarm_time" -> alarmTimeChange(json)
            "set_alarm_enabled" -> alarmEnabled(json)
            "delete_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_deleted, "已删除闹钟"),
                alarmTime(json.optJSONObject("before")),
            )
            "create_calendar_event", "create_calendar_events" -> {
                val count = json.integer("count", 1, 30)
                if (count != null) {
                    AppStrings.quantity(
                        R.plurals.trace_phone_events_created,
                        count,
                        "已创建 %1\$d 个日程",
                        "已创建 %1\$d 个日程",
                        count,
                    )
                } else {
                    AppStrings.get(R.string.trace_phone_event_created, "已创建日程")
                }
            }
            "update_calendar_event" -> calendarChange(json)
            "delete_calendar_event" -> AppStrings.get(R.string.trace_phone_event_deleted, "已删除日程")
            "create_note" -> AppStrings.get(R.string.trace_phone_note_created, "已创建便签")
            "delete_note" -> if (json.optString("mode") == "recycle") {
                AppStrings.get(R.string.trace_phone_note_recycled, "便签已移至回收站")
            } else {
                null
            }
            else -> null
        }
    }

    private fun calendarCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_calendars_empty, "未找到可用日历")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_calendars_found,
            count,
            "找到 %1\$d 个可用日历",
            "找到 %1\$d 个可用日历",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "还有更多")}"
        } else {
            base
        }
    }

    private fun alarmCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_alarms_empty, "暂无闹钟")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_alarms_found,
            count,
            "找到 %1\$d 个闹钟",
            "找到 %1\$d 个闹钟",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "还有更多")}"
        } else {
            base
        }
    }

    private fun timerCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_timers_empty, "暂无活跃计时器")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_timers_found,
            count,
            "找到 %1\$d 个计时器",
            "找到 %1\$d 个计时器",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "还有更多")}"
        } else {
            base
        }
    }

    private fun alarmTime(json: JSONObject?): String? {
        val hour = json?.integer("hour", 0, 23) ?: return null
        val minute = json.integer("minute", 0, 59) ?: return null
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun timerDuration(json: JSONObject): String? {
        val seconds = json.integer("duration_seconds", 1, 86400) ?: return null
        return buildList {
            if (seconds >= 3600) {
                val hours = seconds / 3600
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_hours,
                        hours,
                        "%1\$d 小时",
                        "%1\$d 小时",
                        hours,
                    )
                )
            }
            if (seconds % 3600 >= 60) {
                val minutes = seconds % 3600 / 60
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_minutes,
                        minutes,
                        "%1\$d 分钟",
                        "%1\$d 分钟",
                        minutes,
                    )
                )
            }
            if (seconds % 60 > 0) {
                val remSeconds = seconds % 60
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_seconds,
                        remSeconds,
                        "%1\$d 秒",
                        "%1\$d 秒",
                        remSeconds,
                    )
                )
            }
        }.joinToString(" ")
    }

    private fun alarmTimeChange(json: JSONObject): String {
        val before = alarmTime(json.optJSONObject("before"))
        val alarm = json.optJSONObject("alarm")
        val after = alarmTime(alarm)
        val summary = when {
            before != null && after != null && before == after -> AppStrings.get(
                R.string.trace_phone_alarm_unchanged,
                "闹钟时间未变化 · %1\$s",
                after,
            )
            before != null && after != null -> AppStrings.get(
                R.string.trace_phone_alarm_changed,
                "闹钟时间：%1\$s → %2\$s",
                before,
                after,
            )
            else -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_updated, "已更新闹钟时间"),
                after,
            )
        }
        return if (alarm?.opt("enabled") == false) {
            "$summary · ${AppStrings.get(R.string.trace_phone_alarm_disabled_suffix, "未启用")}"
        } else {
            summary
        }
    }

    private fun alarmEnabled(json: JSONObject): String? {
        val alarm = json.optJSONObject("alarm") ?: return null
        val enabled = alarm.opt("enabled") as? Boolean ?: return null
        val before = json.optJSONObject("before")?.opt("enabled") as? Boolean
        val summary = when {
            before == enabled && enabled ->
                AppStrings.get(R.string.trace_phone_alarm_kept_on, "闹钟保持开启")
            before == enabled ->
                AppStrings.get(R.string.trace_phone_alarm_kept_off, "闹钟保持关闭")
            enabled ->
                AppStrings.get(R.string.trace_phone_alarm_turned_on, "闹钟已开启")
            else ->
                AppStrings.get(R.string.trace_phone_alarm_turned_off, "闹钟已关闭")
        }
        return withDetail(summary, alarmTime(alarm))
    }

    private fun calendarChange(json: JSONObject): String {
        val updated = AppStrings.get(R.string.trace_phone_event_updated, "已更新日程")
        val before = json.optJSONObject("before") ?: return updated
        val after = json.optJSONObject("event") ?: return updated
        val fields = listOf(
            AppStrings.get(R.string.trace_phone_field_title, "标题") to listOf("title"),
            AppStrings.get(R.string.trace_phone_field_time, "时间") to listOf("dtstart", "dtend", "eventTimezone", "allDay"),
            AppStrings.get(R.string.trace_phone_field_location, "地点") to listOf("eventLocation"),
            AppStrings.get(R.string.trace_phone_field_description, "说明") to listOf("description"),
            AppStrings.get(R.string.trace_phone_field_reminders, "提醒") to listOf("reminders"),
        )
        val changes = fields.mapNotNull { (label, keys) ->
            label.takeIf {
                keys.any { key ->
                    before.has(key) && after.has(key) &&
                        calendarValue(before, key) != calendarValue(after, key)
                }
            }
        }
        if (changes.isNotEmpty()) {
            val separator = AppStrings.get(R.string.trace_list_separator, "、")
            return "$updated · ${changes.joinToString(separator)}"
        }
        val keys = fields.flatMap { it.second }
        val complete = keys.all { hasComparableCalendarValue(before, it) && hasComparableCalendarValue(after, it) }
        return if (complete) {
            AppStrings.get(R.string.trace_phone_event_unchanged, "日程未变化")
        } else {
            updated
        }
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
