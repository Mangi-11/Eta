package io.github.mangi.eta.agent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.ScheduledTaskRepository

/**
 * 单一主闹钟调度器。
 *
 * 始终只向系统 [AlarmManager] 注册最近一个即将到期的任务时间点，避免多任务抢占
 * Doze 配额（实测 Android 17 `allow_while_idle_quota=72/h`）。进程被杀或重启后，
 * 由闹钟冷启动或系统广播重新同步。
 */
internal object ScheduledTaskAlarmScheduler {
    const val ACTION_ALARM_FIRE = "io.github.mangi.eta.action.SCHEDULED_TASK_ALARM"
    private const val REQUEST_CODE = 20401
    /** 系统 AlarmManager 的最小提前量，确保刚过期的待执行点也能立即触发。 */
    private const val MIN_FUTURITY_MS = 5_000L

    suspend fun syncNextAlarm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val nextTask = ScheduledTaskRepository.nextEnabledTask(appContext)
        val pendingIntent = buildAlarmPendingIntent(appContext)

        if (nextTask == null || nextTask.nextRunAt == null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }

        val triggerAt = maxOf(nextTask.nextRunAt, nowMs + MIN_FUTURITY_MS)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            AndroidAgentLogger.debug {
                "Scheduled task alarm synced: exact=$canExact delta_ms=${triggerAt - nowMs}"
            }
        } catch (security: SecurityException) {
            // 精确闹钟权限可能在并发瞬间被撤销，降级为非精确唤醒并记录日志。
            AndroidAgentLogger.warn("Exact alarm denied, falling back to inexact: type=${security.safeLogType()}")
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Scheduled task alarm sync failed: type=${failure.safeLogType()}")
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
