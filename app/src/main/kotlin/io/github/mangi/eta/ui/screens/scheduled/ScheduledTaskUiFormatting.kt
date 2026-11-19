package io.github.mangi.eta.ui.screens.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.AppearanceUiStyle
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
    private val DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
    private val TIME_ONLY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun deliveryBadge(mode: ScheduledTaskDeliveryMode): String = when (mode) {
        ScheduledTaskDeliveryMode.ALWAYS -> "每次通知"
        ScheduledTaskDeliveryMode.ON_CHANGE -> "仅变动提醒"
        ScheduledTaskDeliveryMode.SILENT -> "静默归档"
    }

    fun sessionModeLabel(mode: ScheduledTaskSessionMode): String = when (mode) {
        ScheduledTaskSessionMode.ISOLATED -> "每次独立归档（不刷主聊天）"
        ScheduledTaskSessionMode.DEDICATED -> "固定专属对话频道"
    }

    fun statusLabel(status: ScheduledTaskRunStatus?, runningNow: Boolean): String {
        if (runningNow || status == ScheduledTaskRunStatus.RUNNING) return "运行中"
        return when (status) {
            ScheduledTaskRunStatus.DELIVERED -> "已送达"
            ScheduledTaskRunStatus.NO_CHANGE -> "无变化"
            ScheduledTaskRunStatus.SKIPPED -> "已跳过"
            ScheduledTaskRunStatus.FAILED -> "执行失败"
            ScheduledTaskRunStatus.TIMEOUT -> "已超时"
            ScheduledTaskRunStatus.CANCELLED -> "已停止"
            ScheduledTaskRunStatus.RUNNING -> "运行中"
            null -> "尚未运行"
        }
    }

    fun triggerSourceLabel(source: ScheduledTaskTriggerSource): String = when (source) {
        ScheduledTaskTriggerSource.SCHEDULE -> "定时触发"
        ScheduledTaskTriggerSource.MANUAL -> "手动试跑"
        ScheduledTaskTriggerSource.MISFIRE_CATCHUP -> "延迟补跑"
    }

    fun formatTimestamp(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs == null || epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val target = Instant.ofEpochMilli(epochMs).atZone(zone)
        return when {
            target.toLocalDate() == nowDate -> "今天 ${target.format(TIME_ONLY_FORMATTER)}"
            target.toLocalDate() == nowDate.minusDays(1) -> "昨天 ${target.format(TIME_ONLY_FORMATTER)}"
            else -> target.format(DATE_TIME_FORMATTER)
        }
    }

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs < 0L) return ""
        val seconds = durationMs / 1000L
        return when {
            seconds < 1L -> "<1 秒"
            seconds < 60L -> "${seconds} 秒"
            else -> "${seconds / 60} 分 ${seconds % 60} 秒"
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
