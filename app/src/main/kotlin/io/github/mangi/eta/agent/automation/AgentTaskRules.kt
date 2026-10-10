package io.github.mangi.eta.agent.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** 固定结构的本地规则。匹配事件不调用模型。 */
internal object AgentTaskRules {
    val timeTypes = setOf("once", "interval", "daily")
    val eventTypes = setOf(
        "notification_posted",
        "notification_removed",
        "app_foreground",
        "device_unlocked",
        "screen_on",
        "screen_off",
        "charging_connected",
        "charging_disconnected",
        "battery_low",
        "battery_okay",
        "network_connected",
        "network_disconnected",
        "bluetooth_connected",
        "bluetooth_disconnected",
        "headset_connected",
        "headset_disconnected",
        "device_boot",
        "memory_updated",
        "task_completed",
        "task_failed"
    )
    private val filterFields =
        setOf("packageName", "titleContains", "textContains", "taskId", "transport")

    fun validate(raw: JSONObject): JSONObject {
        require(raw.toString().length <= 4000) { "触发规则过长" }
        val type = raw.getString("type")
        require(type in timeTypes || type in eventTypes) { "触发器尚未实现，请先 tasks_triggers" }
        val fields = when (type) {
            "once" -> setOf("type", "at")
            "interval" -> setOf("type", "seconds", "startAt")
            "daily" -> setOf("type", "time", "timeZone", "weekdays")
            else -> setOf("type", "filters")
        } + "conditions"
        require(raw.keys().asSequence().all { it in fields }) { "触发规则包含不支持的字段" }
        when (type) {
            "once" -> parseInstant(raw.getString("at"))
            "interval" -> {
                integer(raw, "seconds", 900, 31_536_000)
                if (raw.has("startAt")) parseInstant(raw.getString("startAt"))
            }

            "daily" -> {
                require(Regex("[0-2][0-9]:[0-5][0-9]").matches(raw.getString("time"))) { "time 需要 HH:mm" }
                LocalTime.parse(raw.getString("time"))
                ZoneId.of(raw.getString("timeZone"))
                raw.optJSONArray("weekdays")?.let { days ->
                    require(days.length() in 1..7) { "weekdays 需要 1–7 个星期序号" }
                    for (i in 0 until days.length()) {
                        val day = days.get(i)
                        require(
                            day is Number && day.toDouble() == day.toLong()
                                .toDouble() && day.toLong() in 1..7
                        ) { "星期序号为 1（周一）至 7" }
                    }
                }
                require(!raw.has("weekdays") || raw.optJSONArray("weekdays") != null) { "weekdays 需要数组" }
            }
        }
        raw.optJSONObject("filters")?.let { filters ->
            require(
                filters.keys().asSequence().all { it in filterFields }) { "不支持此事件过滤字段" }
            filters.keys().forEach { name ->
                require(
                    filters.get(name) is String && filters.getString(name)
                        .isNotBlank() && filters.getString(name).length <= 200
                ) { "过滤值需要 1–200 字符" }
            }
            val allowed = when (type) {
                "notification_posted", "notification_removed" -> setOf(
                    "packageName",
                    "titleContains",
                    "textContains"
                )

                "app_foreground" -> setOf("packageName")
                "task_completed", "task_failed" -> setOf("taskId")
                "network_connected", "network_disconnected" -> setOf("transport")
                else -> emptySet()
            }
            require(
                filters.keys().asSequence().all { it in allowed }) { "该触发器不支持此过滤字段" }
        }
        require(!raw.has("filters") || raw.optJSONObject("filters") != null) { "filters 需要对象" }
        raw.optJSONObject("conditions")?.let { conditions ->
            require(
                conditions.keys().asSequence().all {
                    it in setOf(
                        "charging",
                        "unlocked",
                        "networkConnected"
                    )
                }) { "不支持此附加条件" }
            conditions.keys()
                .forEach { require(conditions.get(it) is Boolean) { "条件值需要 boolean" } }
        }
        require(!raw.has("conditions") || raw.optJSONObject("conditions") != null) { "conditions 需要对象" }
        return JSONObject(raw.toString())
    }

    fun nextTime(rule: JSONObject, after: Long, initial: Boolean): Long? =
        when (rule.getString("type")) {
            "once" -> parseInstant(rule.getString("at")).takeIf { initial }
            "interval" -> {
                val interval = integer(rule, "seconds", 900, 31_536_000) * 1000
                val anchor =
                    rule.optString("startAt").takeIf { it.isNotBlank() }?.let(::parseInstant)
                when {
                    anchor == null -> Math.addExact(after, interval)
                    anchor > after -> anchor
                    else -> Math.addExact(
                        anchor,
                        Math.multiplyExact((after - anchor) / interval + 1, interval)
                    )
                }
            }

            "daily" -> {
                val zone = ZoneId.of(rule.getString("timeZone"))
                val local = Instant.ofEpochMilli(after).atZone(zone)
                val time = LocalTime.parse(rule.getString("time"))
                val days = rule.optJSONArray("weekdays")?.let { arr ->
                    (0 until arr.length()).map { DayOfWeek.of(arr.getInt(it)) }.toSet()
                }
                (0L..7L).map { offset ->
                    local.toLocalDate().plusDays(offset).atTime(time).atZone(zone)
                }
                    .first {
                        it.toInstant()
                            .toEpochMilli() > after && (days == null || it.dayOfWeek in days)
                    }.toInstant().toEpochMilli()
            }

            else -> null
        }

    fun matches(rule: JSONObject, type: String, event: JSONObject): Boolean {
        if (rule.optString("type") != type) return false
        val filters = rule.optJSONObject("filters") ?: JSONObject()
        return filters.keys().asSequence().all { field ->
            val value = filters.getString(field)
            when (field) {
                "titleContains" -> event.optString("title").contains(value, ignoreCase = true)
                "textContains" -> event.optString("text").contains(value, ignoreCase = true)
                else -> event.optString(field) == value
            }
        }
    }

    fun conditionsMatch(rule: JSONObject, state: Map<String, Boolean>): Boolean =
        rule.optJSONObject("conditions")?.let { conditions ->
            conditions.keys().asSequence()
                .all { name -> state[name] == conditions.getBoolean(name) }
        } ?: true

    fun integer(
        args: JSONObject,
        name: String,
        minimum: Long,
        maximum: Long,
        default: Long? = null
    ): Long {
        if (!args.has(name) && default != null) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong()
                .toDouble() && value.toLong() in minimum..maximum
        ) { "$name 需要 $minimum–$maximum 的整数" }
        return value.toLong()
    }

    private fun parseInstant(value: String): Long {
        require(value.length <= 64) { "时间参数过长" }
        return OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }
}
