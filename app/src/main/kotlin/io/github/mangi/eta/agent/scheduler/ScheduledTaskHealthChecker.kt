package io.github.mangi.eta.agent.scheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 定时任务后台运行可靠度自检。
 *
 * 重点检查 Android Doze 10 秒临时豁免窗口下最容易导致长推理失败的三项系统授权：
 * 精确闹钟、电池优化白名单与系统通知权限。
 */
internal data class ScheduledTaskHealthSnapshot(
    val canScheduleExactAlarms: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsEnabled: Boolean,
) {
    val allHealthy: Boolean
        get() = canScheduleExactAlarms && ignoringBatteryOptimizations && notificationsEnabled

    val hasCriticalGap: Boolean
        get() = !canScheduleExactAlarms || !ignoringBatteryOptimizations
}

internal object ScheduledTaskHealthChecker {
    fun inspect(context: Context): ScheduledTaskHealthSnapshot {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)

        val exactAlarm = runCatching { alarmManager?.canScheduleExactAlarms() == true }.getOrDefault(false)
        val batteryUnrestricted = runCatching {
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        }.getOrDefault(false)
        val notifications = runCatching {
            notificationManager?.areNotificationsEnabled() == true
        }.getOrDefault(true)

        return ScheduledTaskHealthSnapshot(
            canScheduleExactAlarms = exactAlarm,
            ignoringBatteryOptimizations = batteryUnrestricted,
            notificationsEnabled = notifications,
        )
    }

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun batterySettingsFallbackIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
