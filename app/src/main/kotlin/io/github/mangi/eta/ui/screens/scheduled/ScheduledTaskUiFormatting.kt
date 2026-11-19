package io.github.mangi.eta.ui.screens.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusIdle
import io.github.mangi.eta.ui.components.StatusRunning
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ScheduledTaskUiFormatting {
    private val TIME_ONLY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun deliveryBadge(mode: ScheduledTaskDeliveryMode): String = when (mode) {
        ScheduledTaskDeliveryMode.ALWAYS ->
            AppStrings.get(R.string.scheduled_delivery_badge_always, "每次通知")
        ScheduledTaskDeliveryMode.ON_CHANGE ->
            AppStrings.get(R.string.scheduled_delivery_badge_on_change, "仅变动提醒")
        ScheduledTaskDeliveryMode.SILENT ->
            AppStrings.get(R.string.scheduled_delivery_badge_silent, "静默归档")
    }

    fun sessionModeLabel(mode: ScheduledTaskSessionMode): String = when (mode) {
        ScheduledTaskSessionMode.ISOLATED ->
            AppStrings.get(R.string.scheduled_session_mode_isolated_label, "每次独立归档（不刷主聊天）")
        ScheduledTaskSessionMode.DEDICATED ->
            AppStrings.get(R.string.scheduled_session_mode_dedicated_label, "固定专属对话频道")
    }

    fun statusLabel(status: ScheduledTaskRunStatus?, runningNow: Boolean): String {
        if (runningNow || status == ScheduledTaskRunStatus.RUNNING) {
            return AppStrings.get(R.string.scheduled_status_running, "运行中")
        }
        return when (status) {
            ScheduledTaskRunStatus.DELIVERED ->
                AppStrings.get(R.string.scheduled_status_delivered, "已送达")
            ScheduledTaskRunStatus.NO_CHANGE ->
                AppStrings.get(R.string.scheduled_status_no_change, "无变化")
            ScheduledTaskRunStatus.SKIPPED ->
                AppStrings.get(R.string.scheduled_status_skipped, "已跳过")
            ScheduledTaskRunStatus.FAILED ->
                AppStrings.get(R.string.scheduled_status_failed, "执行失败")
            ScheduledTaskRunStatus.TIMEOUT ->
                AppStrings.get(R.string.scheduled_status_timeout, "已超时")
            ScheduledTaskRunStatus.CANCELLED ->
                AppStrings.get(R.string.scheduled_status_cancelled, "已停止")
            ScheduledTaskRunStatus.RUNNING ->
                AppStrings.get(R.string.scheduled_status_running, "运行中")
            null ->
                AppStrings.get(R.string.scheduled_status_never_run, "尚未运行")
        }
    }

    fun triggerSourceLabel(source: ScheduledTaskTriggerSource): String = when (source) {
        ScheduledTaskTriggerSource.SCHEDULE ->
            AppStrings.get(R.string.scheduled_trigger_source_schedule, "定时触发")
        ScheduledTaskTriggerSource.MANUAL ->
            AppStrings.get(R.string.scheduled_trigger_source_manual, "手动试跑")
        ScheduledTaskTriggerSource.MISFIRE_CATCHUP ->
            AppStrings.get(R.string.scheduled_trigger_source_misfire_catchup, "延迟补跑")
    }

    fun formatTimestamp(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs == null || epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val target = Instant.ofEpochMilli(epochMs).atZone(zone)
        val hhmm = target.format(TIME_ONLY_FORMATTER)
        return when {
            target.toLocalDate() == nowDate ->
                AppStrings.get(R.string.scheduled_timestamp_today, "今天 %1\$s", hhmm)
            target.toLocalDate() == nowDate.minusDays(1) ->
                AppStrings.get(R.string.scheduled_timestamp_yesterday, "昨天 %1\$s", hhmm)
            else ->
                AppStrings.get(
                    R.string.scheduled_date_time_short,
                    "%1\$d月%2\$d日 %3\$s",
                    target.monthValue,
                    target.dayOfMonth,
                    hhmm,
                )
        }
    }

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs < 0L) return ""
        val seconds = durationMs / 1000L
        return when {
            seconds < 1L -> AppStrings.get(R.string.scheduled_duration_under_1s, "<1 秒")
            seconds < 60L -> AppStrings.get(R.string.scheduled_duration_seconds, "%1\$d 秒", seconds)
            else -> AppStrings.get(
                R.string.scheduled_duration_minutes_seconds,
                "%1\$d 分 %2\$d 秒",
                seconds / 60,
                seconds % 60,
            )
        }
    }

    @Composable
    fun statusDotColor(status: ScheduledTaskRunStatus?, runningNow: Boolean, autoDisabled: Boolean): Color = when {
        runningNow || status == ScheduledTaskRunStatus.RUNNING -> StatusRunning
        autoDisabled || status == ScheduledTaskRunStatus.FAILED || status == ScheduledTaskRunStatus.TIMEOUT -> StatusError
        status == ScheduledTaskRunStatus.DELIVERED -> StatusSuccess
        status == ScheduledTaskRunStatus.SKIPPED -> StatusWarning
        else -> StatusIdle
    }
}
