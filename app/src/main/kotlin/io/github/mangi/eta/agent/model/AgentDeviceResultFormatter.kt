package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
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
            "get_device_environment" -> return AppStrings.get(R.string.trace_dev_env_read, "已读取设备环境")
            "app_usage_summary" -> return AppStrings.get(R.string.trace_dev_usage_read, "已读取应用使用统计")
            "recent_app_activity" -> return AppStrings.get(R.string.trace_dev_activity_read, "已读取应用活动记录")
            "get_device_state", "get_flashlight", "get_hotspot" -> {
                if (json.opt("ok") != true) return null
                val label = when (toolName) {
                    "get_flashlight" -> AppStrings.get(R.string.trace_dev_flashlight, "手电筒")
                    "get_hotspot" -> AppStrings.get(R.string.trace_dev_hotspot, "个人热点")
                    else -> deviceLabel(json.optString("target")) ?: return null
                }
                val enabled = json.opt("enabled") as? Boolean ?: return null
                return AppStrings.get(R.string.trace_dev_kv, "%1\$s：%2\$s", label, booleanState(enabled))
            }
            "media_control" -> return mediaControl(json)
            "network_info" -> return networkInfo(json)
            "device_status" -> return deviceStatus(json)
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_device_state" -> booleanChange(deviceLabel(json.optString("target")) ?: return null, json)
            "set_flashlight" -> booleanChange(AppStrings.get(R.string.trace_dev_flashlight, "手电筒"), json)
            "set_hotspot" -> booleanChange(AppStrings.get(R.string.trace_dev_hotspot, "个人热点"), json)
            "set_volume" -> volume(json)
            "set_brightness" -> brightness(json)
            "set_screen_timeout" -> timeout(number(json, "before"), number(json, "after"))
            "set_sound_mode" -> change(
                AppStrings.get(R.string.trace_dev_sound_mode, "响铃模式"),
                soundMode(integer(json, "before")),
                soundMode(integer(json, "after")),
            )
            "set_do_not_disturb" -> change(
                AppStrings.get(R.string.trace_dev_dnd, "勿扰模式"),
                dndMode(integer(json, "before")),
                dndMode(integer(json, "after")),
            )
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
            "permission_required" -> AppStrings.get(R.string.trace_dev_loc_perm, "位置未授权")
            "background_permission_required" -> AppStrings.get(R.string.trace_dev_loc_bg_perm, "后台位置未授权")
            "location_disabled" -> AppStrings.get(R.string.trace_dev_loc_disabled, "系统定位已关闭")
            "unavailable" -> AppStrings.get(R.string.trace_dev_loc_none, "暂无最近位置")
            else -> null
        }
        if (status != null) return "$time · $status"
        val latitude = number(location, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(location, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) {
            return "$time · ${AppStrings.get(R.string.trace_dev_loc_unavailable, "位置不可用")}"
        }
        val age = integer(location, "age_s")?.let { formatAge(it.toLong()) }
        return "$time · ${formatRecentLocation(latitude, longitude, age)}"
    }

    private fun currentLocation(json: JSONObject): String? {
        val latitude = number(json, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(json, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) {
            return AppStrings.get(R.string.trace_dev_loc_unavailable, "位置不可用")
        }
        val age = number(json, "age_ms")?.let { ms -> formatAge((ms / 1000).toLong()) }
        return formatRecentLocation(latitude, longitude, age)
    }

    private fun formatAge(seconds: Long): String = when {
        seconds < 60 -> AppStrings.quantity(
            R.plurals.trace_dev_age_seconds,
            seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            "%1\$d 秒前",
            "%1\$d 秒前",
            seconds,
        )
        seconds < 3600 -> {
            val minutes = seconds / 60
            AppStrings.quantity(
                R.plurals.trace_dev_age_minutes,
                minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                "%1\$d 分钟前",
                "%1\$d 分钟前",
                minutes,
            )
        }
        seconds < 86400 -> {
            val hours = seconds / 3600
            AppStrings.quantity(
                R.plurals.trace_dev_age_hours,
                hours.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                "%1\$d 小时前",
                "%1\$d 小时前",
                hours,
            )
        }
        else -> {
            val days = seconds / 86400
            AppStrings.quantity(
                R.plurals.trace_dev_age_days,
                days.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                "%1\$d 天前",
                "%1\$d 天前",
                days,
            )
        }
    }

    private fun formatRecentLocation(latitude: Double, longitude: Double, age: String?): String =
        if (age != null) {
            AppStrings.get(
                R.string.trace_dev_recent_location_age,
                "最近位置：%1\$s, %2\$s（%3\$s）",
                decimal(latitude),
                decimal(longitude),
                age,
            )
        } else {
            AppStrings.get(
                R.string.trace_dev_recent_location,
                "最近位置：%1\$s, %2\$s",
                decimal(latitude),
                decimal(longitude),
            )
        }

    private fun booleanChange(label: String, json: JSONObject): String? = change(
        label,
        (json.opt("before") as? Boolean)?.let(::booleanState),
        (json.opt("after") as? Boolean)?.let(::booleanState),
        isBoolean = true,
    )

    private fun volume(json: JSONObject): String? {
        val streamLabel = when (json.optString("stream")) {
            "media" -> AppStrings.get(R.string.trace_dev_vol_media, "媒体音量")
            "alarm" -> AppStrings.get(R.string.trace_dev_vol_alarm, "闹钟音量")
            "ring" -> AppStrings.get(R.string.trace_dev_vol_ring, "铃声音量")
            "notification" -> AppStrings.get(R.string.trace_dev_vol_notification, "通知音量")
            "voice_call" -> AppStrings.get(R.string.trace_dev_vol_call, "通话音量")
            "system" -> AppStrings.get(R.string.trace_dev_vol_system, "系统音量")
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
                AppStrings.get(R.string.trace_dev_vol_level, "%1\$d 档", it)
            }
        }
        return change(streamLabel, display(before), display(after), unchanged = before == after)
    }

    private fun brightness(json: JSONObject): String? {
        val before = number(json, "before")?.takeIf { it in 0.0..100.0 }
        val after = number(json, "after")?.takeIf { it in 0.0..100.0 } ?: return null
        fun display(value: Double): String = "${decimal((value * 10).roundToInt() / 10.0)}%"
        return change(
            AppStrings.get(R.string.trace_dev_brightness, "亮度"),
            before?.let(::display),
            display(after),
            unchanged = before == after,
        )
    }

    private fun timeout(before: Double?, after: Double?): String? {
        fun display(value: Double?): String? = value?.takeIf { it >= 0 && it <= Long.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.let {
            if (it > 0 && it % 60_000 == 0.0) {
                AppStrings.get(R.string.trace_dev_unit_min, "%1\$s 分钟", decimal(it / 60_000))
            } else {
                AppStrings.get(R.string.trace_dev_unit_sec, "%1\$s 秒", decimal(it / 1000))
            }
        }
        return change(
            AppStrings.get(R.string.trace_dev_screen_timeout, "自动息屏"),
            display(before),
            display(after),
            unchanged = before == after,
        )
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
        return when (action) {
            "force_stop" -> when {
                before == true && after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_kept_stopped_pkg, "应用 %1\$s：保持停止", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_kept_stopped, "应用：保持停止")
                }
                after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_stopped_pkg, "已停止应用 %1\$s", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_stopped, "已停止应用")
                }
                else -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_not_stopped_pkg, "应用 %1\$s：未停止", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_not_stopped, "应用：未停止")
                }
            }
            "freeze" -> when {
                before == false && !after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_kept_frozen_pkg, "应用 %1\$s：保持冻结", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_kept_frozen, "应用：保持冻结")
                }
                !after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_frozen_pkg, "已冻结应用 %1\$s", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_frozen, "已冻结应用")
                }
                else -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_kept_enabled_pkg, "应用 %1\$s：保持启用", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_kept_enabled, "应用：保持启用")
                }
            }
            "unfreeze" -> when {
                before == true && after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_kept_enabled_pkg, "应用 %1\$s：保持启用", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_kept_enabled, "应用：保持启用")
                }
                after -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_unfrozen_pkg, "已解冻应用 %1\$s", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_unfrozen, "已解冻应用")
                }
                else -> if (packageName != null) {
                    AppStrings.get(R.string.trace_dev_app_kept_frozen_pkg, "应用 %1\$s：保持冻结", packageName)
                } else {
                    AppStrings.get(R.string.trace_dev_app_kept_frozen, "应用：保持冻结")
                }
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
            "system" to "accelerometer_rotation" ->
                AppStrings.get(R.string.trace_dev_label_auto_rotate, "自动旋转")
            "system" to "screen_brightness_mode" ->
                AppStrings.get(R.string.trace_dev_label_auto_brightness, "自动亮度")
            "global" to "low_power" ->
                AppStrings.get(R.string.trace_dev_label_battery_saver, "省电模式")
            "global" to "airplane_mode_on" ->
                AppStrings.get(R.string.trace_dev_label_airplane_mode, "飞行模式")
            else -> null
        }
        if (booleanLabel != null) {
            fun display(value: String?): String? = when (value) {
                "0" -> AppStrings.get(R.string.trace_dev_state_off, "关闭")
                "1" -> AppStrings.get(R.string.trace_dev_state_on, "开启")
                else -> null
            }
            return change(booleanLabel, display(before), display(after), isBoolean = true)
        }
        if (namespace == "system" && key == "screen_off_timeout") {
            return timeout(before?.toLongOrNull()?.toDouble(), after.toLongOrNull()?.toDouble())
        }
        if (namespace == "global" && key == "zen_mode") {
            return change(
                AppStrings.get(R.string.trace_dev_dnd, "勿扰模式"),
                dndMode(before?.toIntOrNull()),
                dndMode(after.toIntOrNull()),
            )
        }
        // 任意设置值可能含认证信息或用户正文，摘要只保留是否变化。
        return if (before == after) {
            AppStrings.get(R.string.trace_dev_setting_unchanged, "系统设置未变化")
        } else {
            AppStrings.get(R.string.trace_dev_setting_changed, "系统设置已修改")
        }
    }

    private fun mediaControl(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        return when (json.optString("action")) {
            "play" -> AppStrings.get(R.string.trace_dev_media_play, "已发送播放指令")
            "pause" -> AppStrings.get(R.string.trace_dev_media_pause, "已发送暂停指令")
            "play_pause" -> AppStrings.get(R.string.trace_dev_media_play_pause, "已发送播放/暂停切换指令")
            "next" -> AppStrings.get(R.string.trace_dev_media_next, "已发送下一首指令")
            "previous" -> AppStrings.get(R.string.trace_dev_media_previous, "已发送上一首指令")
            "stop" -> AppStrings.get(R.string.trace_dev_media_stop, "已发送停止播放指令")
            else -> null
        }
    }

    private fun networkInfo(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val connected = json.opt("connected") as? Boolean ?: return null
        val validated = json.opt("validated") as? Boolean ?: return null
        val transports = json.optJSONArray("transports") ?: return null
        val labels = (0 until transports.length()).mapNotNull { index ->
            when (transports.opt(index) as? String ?: return null) {
                "wifi" -> "Wi-Fi"
                "cellular" -> AppStrings.get(R.string.trace_dev_net_cellular, "移动网络")
                "ethernet" -> AppStrings.get(R.string.trace_dev_net_ethernet, "以太网")
                "vpn" -> "VPN"
                else -> null
            }
        }.distinct()
        if (!connected) return AppStrings.get(R.string.trace_dev_net_disconnected, "网络：未连接")
        val label = labels.joinToString(" / ").ifEmpty {
            AppStrings.get(R.string.trace_dev_net_generic, "网络")
        }
        return if (validated) {
            AppStrings.get(R.string.trace_dev_net_connected, "%1\$s：已连接互联网", label)
        } else {
            AppStrings.get(R.string.trace_dev_net_unvalidated, "%1\$s：已连接，互联网未验证", label)
        }
    }

    private fun deviceStatus(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val percent = integer(json, "battery_percent")?.takeIf { it in 0..100 } ?: return null
        val charging = json.opt("charging") as? Boolean ?: return null
        return if (charging) {
            AppStrings.get(R.string.trace_dev_battery_charging, "电量 %1\$d%% · 正在充电", percent)
        } else {
            AppStrings.get(R.string.trace_dev_battery_discharging, "电量 %1\$d%% · 未充电", percent)
        }
    }

    private fun change(
        label: String,
        before: String?,
        after: String?,
        unchanged: Boolean = before == after,
        isBoolean: Boolean = false,
    ): String? {
        if (after == null) return null
        val isNumeric = after.firstOrNull()?.isDigit() == true || after.endsWith("%")
        return when {
            before == null || (!unchanged && before == after) -> when {
                isBoolean -> AppStrings.get(R.string.trace_dev_change_bool_set, "%1\$s：已%2\$s", label, after)
                isNumeric -> AppStrings.get(R.string.trace_dev_change_set_num, "%1\$s：已设为 %2\$s", label, after)
                else -> AppStrings.get(R.string.trace_dev_change_set, "%1\$s：已设为%2\$s", label, after)
            }
            unchanged -> if (isNumeric) {
                AppStrings.get(R.string.trace_dev_change_kept_num, "%1\$s：保持 %2\$s", label, after)
            } else {
                AppStrings.get(R.string.trace_dev_change_kept, "%1\$s：保持%2\$s", label, after)
            }
            else -> AppStrings.get(R.string.trace_dev_change_arrow, "%1\$s：%2\$s → %3\$s", label, before, after)
        }
    }

    private fun booleanState(value: Boolean): String = if (value) {
        AppStrings.get(R.string.trace_dev_state_on, "开启")
    } else {
        AppStrings.get(R.string.trace_dev_state_off, "关闭")
    }

    private fun deviceLabel(target: String): String? = when (target) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> AppStrings.get(R.string.trace_dev_label_bluetooth, "蓝牙")
        "mobile_data" -> AppStrings.get(R.string.trace_dev_label_mobile_data, "移动数据")
        "airplane_mode" -> AppStrings.get(R.string.trace_dev_label_airplane_mode, "飞行模式")
        "location" -> AppStrings.get(R.string.trace_dev_label_location, "定位")
        "nfc" -> "NFC"
        "battery_saver" -> AppStrings.get(R.string.trace_dev_label_battery_saver, "省电模式")
        "auto_rotate" -> AppStrings.get(R.string.trace_dev_label_auto_rotate, "自动旋转")
        "auto_brightness" -> AppStrings.get(R.string.trace_dev_label_auto_brightness, "自动亮度")
        "dark_mode" -> AppStrings.get(R.string.trace_dev_label_dark_mode, "深色模式")
        "night_light" -> AppStrings.get(R.string.trace_dev_label_night_light, "护眼模式")
        else -> null
    }

    private fun soundMode(value: Int?): String? = when (value) {
        0 -> AppStrings.get(R.string.trace_dev_sound_silent, "静音")
        1 -> AppStrings.get(R.string.trace_dev_sound_vibrate, "振动")
        2 -> AppStrings.get(R.string.trace_dev_sound_normal, "正常")
        else -> null
    }

    private fun dndMode(value: Int?): String? = when (value) {
        0 -> AppStrings.get(R.string.trace_dev_state_off, "关闭")
        1 -> AppStrings.get(R.string.trace_dev_dnd_priority, "优先事项")
        2 -> AppStrings.get(R.string.trace_dev_dnd_silent, "全部静音")
        3 -> AppStrings.get(R.string.trace_dev_dnd_alarms, "仅闹钟")
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
