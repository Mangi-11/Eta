package io.github.mangi.eta.agent.scheduler

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内部精确闹钟与通知快捷操作接收器（`exported="false"`）。
 *
 * 在 `goAsync()` 保护内立刻把执行权移交 [ScheduledTaskRunner]，后者在 Android Doze 10 秒
 * 临时豁免窗口内完成 WakeLock 与前台服务接管。
 */
internal class ScheduledTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    ScheduledTaskAlarmScheduler.ACTION_ALARM_FIRE -> {
                        ScheduledTaskRunner.onAlarmFired(appContext)
                    }
                    ACTION_RUN_TASK_NOW -> {
                        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
                        if (taskId != null) {
                            ScheduledTaskRunner.triggerManualRun(appContext, taskId)
                        }
                    }
                }
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Scheduled alarm receiver failed: type=${failure.safeLogType()}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RUN_TASK_NOW = "io.github.mangi.eta.action.RUN_SCHEDULED_TASK_NOW"
        const val EXTRA_TASK_ID = "scheduled_task_id"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * 系统生命周期广播接收器：重启、改时间、换时区、应用覆盖安装、精确闹钟权限变化时重建调度。
 *
 * 严格不在开机广播内直接拉起前台服务执行过期模型任务，避免触碰 Android 15+ 开机 FGS 限制。
 */
internal class ScheduledTaskSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val recomputeAll = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val pending = goAsync()
        receiverScope.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    action == Intent.ACTION_TIME_CHANGED ||
                    action == Intent.ACTION_TIMEZONE_CHANGED ||
                    action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                ) {
                    ScheduledTaskRepository.reconcileAfterRestartOrClockChange(
                        recomputeAllSchedules = recomputeAll,
                        rescheduleAlarm = true,
                        context = appContext,
                    )
                }
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Scheduled system reconcile failed: type=${failure.safeLogType()}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
