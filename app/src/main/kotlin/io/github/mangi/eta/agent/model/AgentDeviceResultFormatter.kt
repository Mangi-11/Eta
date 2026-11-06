package io.github.mangi.eta.agent.model

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

internal object AgentDeviceResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null
        when (toolName) {
            "get_current_context" -> return currentContext(json)
            "get_current_location" -> return currentLocation(json)
            "get_device_environment" -> return "已读取设备环境"
            "app_usage_summary" -> return "已读取应用使用统计"
            "recent_app_activity" -> return "已读取应用活动记录"
            "get_device_state", "get_flashlight", "get_hotspot" -> {
                if (json.opt("ok") != true) return null
                val label = when (toolName) {
                    "get_flashlight" -> "手电筒"
                    "get_hotspot" -> "个人热点"
                    else -> deviceLabel(json.optString("target")) ?: return null
                }
                val enabled = json.opt("enabled") as? Boolean ?: return null
                return "$label：${booleanState(enabled)}"
            }
            "media_control" -> return mediaControl(json)
            "network_info" -> return networkInfo(json)
            "device_status" -> return deviceStatus(json)
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_device_state" -> booleanChange(deviceLabel(json.optString("target")) ?: return null, json)
            "set_flashlight" -> booleanChange("手电筒", json)
            "set_hotspot" -> booleanChange("个人热点", json)
            "set_volume" -> volume(json)
            "set_brightness" -> brightness(json)
            "set_screen_timeout" -> timeout(number(json, "before"), number(json, "after"))
            "set_sound_mode" -> change("响铃模式", soundMode(integer(json, "before")), soundMode(integer(json, "after")))
            "set_do_not_disturb" -> change("勿扰模式", dndMode(integer(json, "before")), dndMode(integer(json, "after")))
            "app_state_control" -> applicationState(json)
            "set_setting" -> setting(json)
            else -> null
        }
    }

    private fun currentContext(json: JSONObject): String? {
        val datetime = json.opt("datetime") as? String ?: return null
        val time = try {
            OffsetDateTime.parse(datetime).format(CONTEXT_TIME_FORMAT)
        } catch (_: DateTimeParseException) {
            return null
        }
        val location = json.optJSONObject("location") ?: return time
        val status = when (location.optString("status")) {
            "permission_required" -> "位置未授权"
            "background_permission_required" -> "后台位置未授权"
            "location_disabled" -> "系统定位已关闭"
            "unavailable" -> "暂无最近位置"
            else -> null
        }
        if (status != null) return "$time · $status"
        val latitude = number(location, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(location, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) return "$time · 位置不可用"
        val age = integer(location, "age_s")?.let {
            when {
                it < 60 -> "$it 秒前"
                it < 3600 -> "${it / 60} 分钟前"
                it < 86400 -> "${it / 3600} 小时前"
                else -> "${it / 86400} 天前"
            }
        }
        return "$time · 最近位置：${decimal(latitude)}, ${decimal(longitude)}" +
            if (age != null) "（$age）" else ""
    }

    private fun currentLocation(json: JSONObject): String? {
        val latitude = number(json, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(json, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) return "位置不可用"
        val ageMs = number(json, "age_ms")
        val age = ageMs?.let { ms ->
            val seconds = (ms / 1000).toLong()
            when {
                seconds < 60 -> "$seconds 秒前"
                seconds < 3600 -> "${seconds / 60} 分钟前"
                seconds < 86400 -> "${seconds / 3600} 小时前"
                else -> "${seconds / 86400} 天前"
            }
        }
        return "最近位置：${decimal(latitude)}, ${decimal(longitude)}" +
            if (age != null) "（$age）" else ""
    }

    private fun booleanChange(label: String, json: JSONObject): String? = change(
        label,
        (json.opt("before") as? Boolean)?.let(::booleanState),
        (json.opt("after") as? Boolean)?.let(::booleanState),
        withoutBefore = "已",
    )

    private fun volume(json: JSONObject): String? {
        val stream = when (json.optString("stream")) {
            "media" -> "媒体"
            "alarm" -> "闹钟"
            "ring" -> "铃声"
            "notification" -> "通知"
            "voice_call" -> "通话"
            "system" -> "系统"
            else -> return null
        }
        val before = integer(json, "before")
        val after = integer(json, "after") ?: return null
        val min = integer(json, "min_level")
        val max = integer(json, "max_level")
        val range = if (min != null && max != null && max > min && after in min..max) min..max else null
        fun display(level: Int?): String? = level?.let {
            if (range != null) {
                if (it !in range) null else "${((it - range.first) * 100.0 / (range.last - range.first)).roundToInt()}%"
            } else {
                "$it 档"
            }
        }
        return change("${stream}音量", display(before), display(after), unchanged = before == after)
    }

    private fun brightness(json: JSONObject): String? {
        val before = number(json, "before")?.takeIf { it in 0.0..100.0 }
        val after = number(json, "after")?.takeIf { it in 0.0..100.0 } ?: return null
        fun display(value: Double): String = "${decimal((value * 10).roundToInt() / 10.0)}%"
        return change("亮度", before?.let(::display), display(after), unchanged = before == after)
    }

    private fun timeout(before: Double?, after: Double?): String? {
        fun display(value: Double?): String? = value?.takeIf { it >= 0 && it <= Long.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.let {
            if (it > 0 && it % 60_000 == 0.0) "${decimal(it / 60_000)} 分钟"
            else "${decimal(it / 1000)} 秒"
        }
        return change("自动息屏", display(before), display(after), unchanged = before == after)
    }

    private fun applicationState(json: JSONObject): String? {
        val action = json.optString("action")
        val field = when (action) {
            "force_stop" -> "stopped"
            "freeze", "unfreeze" -> "enabled"
            else -> return null
        }
        if (json.optString("state_field") != field) return null
        val after = json.opt("after") as? Boolean ?: return null
        val before = json.opt("before") as? Boolean
        val packageName = (json.opt("package_name") as? String)
            ?.takeIf { it.length <= 255 && PACKAGE_NAME.matches(it) }
            ?.let { if (it.length > 60) it.take(59) + "…" else it }
        val target = packageName?.let { "应用 $it" } ?: "应用"
        return when (action) {
            "force_stop" -> when {
                before == true && after -> "$target：保持停止"
                after -> "已停止$target"
                else -> "$target：未停止"
            }
            "freeze" -> when {
                before == false && !after -> "$target：保持冻结"
                !after -> "已冻结$target"
                else -> "$target：保持启用"
            }
            "unfreeze" -> when {
                before == true && after -> "$target：保持启用"
                after -> "已解冻$target"
                else -> "$target：保持冻结"
            }
            else -> null
        }
    }

    private fun setting(json: JSONObject): String? {
        val before = json.opt("before") as? String
        val after = json.opt("after") as? String ?: return null
        val namespace = json.optString("namespace")
        val key = json.optString("key")
        val booleanLabel = when (namespace to key) {
            "system" to "accelerometer_rotation" -> "自动旋转"
            "system" to "screen_brightness_mode" -> "自动亮度"
            "global" to "low_power" -> "省电模式"
            "global" to "airplane_mode_on" -> "飞行模式"
            else -> null
        }
        if (booleanLabel != null) {
            fun display(value: String?): String? = when (value) {
                "0" -> "关闭"
                "1" -> "开启"
                else -> null
            }
            return change(booleanLabel, display(before), display(after), withoutBefore = "已")
        }
        if (namespace == "system" && key == "screen_off_timeout") {
            return timeout(before?.toLongOrNull()?.toDouble(), after.toLongOrNull()?.toDouble())
        }
        if (namespace == "global" && key == "zen_mode") {
            return change("勿扰模式", dndMode(before?.toIntOrNull()), dndMode(after.toIntOrNull()))
        }
        // 任意设置值可能含认证信息或用户正文，摘要只保留是否变化。
        return if (before == after) "系统设置未变化" else "系统设置已修改"
    }

    private fun mediaControl(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val action = when (json.optString("action")) {
            "play" -> "播放"
            "pause" -> "暂停"
            "play_pause" -> "播放/暂停切换"
            "next" -> "下一首"
            "previous" -> "上一首"
            "stop" -> "停止播放"
            else -> return null
        }
        return "已发送${action}指令"
    }

    private fun networkInfo(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val connected = json.opt("connected") as? Boolean ?: return null
        val validated = json.opt("validated") as? Boolean ?: return null
        val transports = json.optJSONArray("transports") ?: return null
        val labels = (0 until transports.length()).mapNotNull { index ->
            when (transports.opt(index) as? String ?: return null) {
                "wifi" -> "Wi-Fi"
                "cellular" -> "移动网络"
                "ethernet" -> "以太网"
                "vpn" -> "VPN"
                else -> null
            }
        }.distinct()
        if (!connected) return "网络：未连接"
        val label = labels.joinToString(" / ").ifEmpty { "网络" }
        return "$label：${if (validated) "已连接互联网" else "已连接，互联网未验证"}"
    }

    private fun deviceStatus(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val percent = integer(json, "battery_percent")?.takeIf { it in 0..100 } ?: return null
        val charging = json.opt("charging") as? Boolean ?: return null
        return "电量 $percent% · ${if (charging) "正在充电" else "未充电"}"
    }

    private fun change(
        label: String,
        before: String?,
        after: String?,
        unchanged: Boolean = before == after,
        withoutBefore: String = "已设为",
    ): String? {
        if (after == null) return null
        val prefix = if (withoutBefore.endsWith("为") && (after.firstOrNull()?.isDigit() == true || after.endsWith("%"))) "$withoutBefore " else withoutBefore
        val keep = if (after.firstOrNull()?.isDigit() == true || after.endsWith("%")) "保持 " else "保持"
        return when {
            before == null -> "$label：$prefix$after"
            unchanged -> "$label：$keep$after"
            before == after -> "$label：$prefix$after"
            else -> "$label：$before → $after"
        }
    }

    private fun booleanState(value: Boolean): String = if (value) "开启" else "关闭"

    private fun deviceLabel(target: String): String? = when (target) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "蓝牙"
        "mobile_data" -> "移动数据"
        "airplane_mode" -> "飞行模式"
        "location" -> "定位"
        "nfc" -> "NFC"
        "battery_saver" -> "省电模式"
        "auto_rotate" -> "自动旋转"
        "auto_brightness" -> "自动亮度"
        "dark_mode" -> "深色模式"
        "night_light" -> "护眼模式"
        else -> null
    }

    private fun soundMode(value: Int?): String? = when (value) {
        0 -> "静音"
        1 -> "振动"
        2 -> "正常"
        else -> null
    }

    private fun dndMode(value: Int?): String? = when (value) {
        0 -> "关闭"
        1 -> "优先事项"
        2 -> "全部静音"
        3 -> "仅闹钟"
        else -> null
    }

    private fun number(json: JSONObject, key: String): Double? =
        (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun integer(json: JSONObject, key: String): Int? = number(json, key)
        ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.toInt()

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val CONTEXT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx", Locale.ROOT)
}
