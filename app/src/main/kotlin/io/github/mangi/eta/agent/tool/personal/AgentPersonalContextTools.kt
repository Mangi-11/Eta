package io.github.mangi.eta.agent.tool.personal

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.repository.NotificationHistoryRepository
import org.json.JSONArray
import org.json.JSONObject

internal class AgentPersonalContextTools(private val context: Context) {
    private val notificationHistory by lazy { NotificationHistoryRepository(context) }

    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "search_notification_history" -> sensitive(searchNotificationHistory(args))
        "recent_app_activity" -> sensitive(recentAppActivity(args))
        "app_usage_summary" -> sensitive(appUsageSummary(args))
        "get_current_location" -> sensitive(currentLocation())
        "get_device_environment" -> sensitive(deviceEnvironment())
        else -> null
    }

    private fun searchNotificationHistory(args: JSONObject): String {
        if (!AgentNotificationHistoryService.isEnabled(context)) {
            return error(
                "NOTIFICATION_HISTORY_ACCESS_REQUIRED",
                "请先在权限健康页授予 Eta 通知使用权；授权后开始记录最近 7 天通知",
            )
        }
        return notificationHistory.search(
            query = args.optString("query").trim(),
            packageName = args.optString("package_name").trim(),
            maxAgeHours = args.optInt("max_age_hours", 24).coerceIn(1, 168),
            limit = args.optInt("limit", 20).coerceIn(1, 50),
        )
    }

    private fun recentAppActivity(args: JSONObject): String {
        if (!hasUsageAccess(context)) return usageAccessError()
        val maxAgeHours = args.optInt("max_age_hours", 24).coerceIn(1, 168)
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val packageFilter = args.optString("package_name").trim()
        val end = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java)
            ?.queryEvents(end - maxAgeHours * HOUR_MS, end)
            ?: return error("APP_USAGE_UNAVAILABLE", "系统未返回应用活动记录")
        val rows = ArrayDeque<JSONObject>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (packageFilter.isNotBlank() && event.packageName != packageFilter) continue
            rows.addFirst(
                JSONObject()
                    .put("package_name", event.packageName)
                    .put("app_name", appName(event.packageName))
                    .put("activity", event.className)
                    .put("resumed_at", event.timeStamp),
            )
            while (rows.size > limit) rows.removeLast()
        }
        return ok("recent_app_activity")
            .put("items", JSONArray(rows))
            .put("count", rows.size)
            .put("window_hours", maxAgeHours)
            .toString()
    }

    private fun appUsageSummary(args: JSONObject): String {
        if (!hasUsageAccess(context)) return usageAccessError()
        val maxAgeHours = args.optInt("max_age_hours", 24).coerceIn(1, 168)
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val end = System.currentTimeMillis()
        val stats = context.getSystemService(UsageStatsManager::class.java)
            ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - maxAgeHours * HOUR_MS, end)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, entries) ->
                JSONObject()
                    .put("package_name", packageName)
                    .put("app_name", appName(packageName))
                    .put("foreground_ms", entries.sumOf { it.totalTimeInForeground })
                    .put("last_used_at", entries.maxOf { it.lastTimeUsed })
            }
            .sortedByDescending { it.optLong("foreground_ms") }
            .take(limit)
        return ok("app_usage_summary")
            .put("items", JSONArray(stats))
            .put("count", stats.size)
            .put("window_hours", maxAgeHours)
            .toString()
    }

    private fun currentLocation(): String = when (val result = DeviceLocationProvider.latest(context)) {
        is DeviceLocationProvider.Result.Available -> ok("get_current_location")
            .put("latitude", result.latitude)
            .put("longitude", result.longitude)
            .put("accuracy_m", result.accuracyMeters)
            .put("age_ms", result.ageMillis)
            .toString()
        is DeviceLocationProvider.Result.Unavailable -> error(
            "LOCATION_UNAVAILABLE",
            when (result.status) {
                "permission_required" -> "请先授予位置权限"
                "background_permission_required" -> "请将位置权限设为始终允许"
                "location_disabled" -> "系统定位服务已关闭"
                else -> "系统没有可用的最近位置"
            },
        )
    }

    private fun deviceEnvironment(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val displays = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val notification = context.getSystemService(NotificationManager::class.java)
        val routes = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { device ->
            JSONObject()
                .put("type", deviceType(device.type))
                .put("product_name", device.productName?.toString())
                .put("is_sink", device.isSink)
        }
        return ok("get_device_environment")
            .put("interactive", power?.isInteractive)
            .put("device_locked", keyguard?.isDeviceLocked)
            .put("ringer_mode", audio?.ringerMode?.let(::ringerMode))
            .put("dnd_filter", notification?.currentInterruptionFilter?.let(::interruptionFilter))
            .put("audio_outputs", JSONArray(routes))
            .put("display_count", displays.size)
            .put("external_display_count", displays.count { it.displayId != android.view.Display.DEFAULT_DISPLAY })
            .toString()
    }

    private fun appName(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun usageAccessError(): String = error(
        "APP_USAGE_ACCESS_REQUIRED",
        "请先在权限健康页授予 Eta 使用情况访问权",
    )

    private fun ok(tool: String) = JSONObject().put("ok", true).put("tool", tool)

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    companion object {
        const val HOUR_MS = 60L * 60 * 1_000

        internal fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            return appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }

        private fun deviceType(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_audio"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_audio"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth_le_audio"
            else -> "other_$type"
        }

        private fun ringerMode(mode: Int): String = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        }

        private fun interruptionFilter(filter: Int): String = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> "unknown"
        }
    }
}
