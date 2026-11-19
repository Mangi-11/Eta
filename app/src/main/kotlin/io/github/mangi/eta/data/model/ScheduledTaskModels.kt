package io.github.mangi.eta.data.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import kotlinx.serialization.Serializable

/**
 * 定时任务触发类型。
 *
 * 与任务执行体解耦，便于后续在不改动执行和通知链路的前提下扩展事件触发器。
 */
@Serializable
enum class ScheduledTaskTriggerKind(val wireValue: String) {
    ONCE("once"),
    DAILY("daily"),
    WEEKDAYS("weekdays"),
    WEEKLY("weekly"),
    INTERVAL("interval"),
    CRON("cron");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskTriggerKind =
            entries.firstOrNull { it.wireValue == raw } ?: DAILY
    }
}

/** 定时任务结果交付方式。 */
@Serializable
enum class ScheduledTaskDeliveryMode(val wireValue: String) {
    /** 每次完成后发送结论通知（早报、晚报、提醒）。 */
    ALWAYS("always"),
    /** 仅发现值得关注的变化时发送通知，无变化静默归档（监控、巡检）。 */
    ON_CHANGE("on_change"),
    /** 仅后台执行并归档，不发常规完成通知（失败告警除外）。 */
    SILENT("silent");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskDeliveryMode =
            entries.firstOrNull { it.wireValue == raw } ?: ALWAYS
    }
}

/** 定时任务结果会话归属模式。 */
@Serializable
enum class ScheduledTaskSessionMode(val wireValue: String) {
    /** 每次运行生成独立归档，不刷屏主聊天列表；点通知或时间线可随时打开并继续追问。 */
    ISOLATED("isolated"),
    /** 固定归属到该任务的专属会话频道，每次运行按时间续写。 */
    DEDICATED("dedicated");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskSessionMode =
            entries.firstOrNull { it.wireValue == raw } ?: ISOLATED
    }
}

/** 设备关机/休眠错过触发点后的处理策略。 */
@Serializable
enum class ScheduledTaskMisfirePolicy(val wireValue: String) {
    /** 在过期窗口内最多补跑最近一次，超出窗口跳过（避免早报半夜补发）。 */
    RUN_ONCE_WITHIN_WINDOW("run_once_within_window"),
    /** 错过即跳过，等待下一个排期点。 */
    SKIP("skip");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskMisfirePolicy =
            entries.firstOrNull { it.wireValue == raw } ?: RUN_ONCE_WITHIN_WINDOW
    }
}

/** 任务触发排期参数。 */
@Serializable
data class ScheduledTaskSchedule(
    val kind: ScheduledTaskTriggerKind = ScheduledTaskTriggerKind.DAILY,
    /** 一次性任务的绝对触发时间戳（毫秒）。 */
    val triggerAtEpochMs: Long? = null,
    /** 本地小时（0..23），用于 DAILY / WEEKDAYS / WEEKLY。 */
    val hour: Int = 9,
    /** 本地分钟（0..59），用于 DAILY / WEEKDAYS / WEEKLY。 */
    val minute: Int = 0,
    /** ISO-8601 星期集合（1=周一 .. 7=周日），用于 WEEKLY。 */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** 固定间隔分钟数（最少 5 分钟），用于 INTERVAL。 */
    val intervalMinutes: Int = 60,
    /** 标准 5 字段 Cron 表达式（minute hour dom month dow），用于 CRON。 */
    val cronExpression: String = "",
    /** IANA 时区 ID；空字符串表示跟随设备当前本地时区。 */
    val timezoneId: String = "",
)

/** 可选的安静免打扰时段；处于该窗口内的周期触发将被跳过。 */
@Serializable
data class ScheduledTaskQuietHours(
    val enabled: Boolean = false,
    val startHour: Int = 23,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 30,
)

/**
 * 定时任务的单任务能力裁剪开关。
 *
 * 运行时仍与全局开关取交集，只能收窄权限，不能绕过用户的总开关。
 * 默认关闭 [guiTools]，保证手机锁屏/熄屏时后台静默安全运行。
 */
@Serializable
data class ScheduledTaskCapabilities(
    val browserTools: Boolean = true,
    val deviceReadTools: Boolean = true,
    val deviceActionTools: Boolean = false,
    val terminalTools: Boolean = true,
    val mcpTools: Boolean = true,
    val guiTools: Boolean = false,
)

/** 定时任务单次执行终态或进行中状态。 */
@Serializable
enum class ScheduledTaskRunStatus(val wireValue: String) {
    RUNNING("running"),
    DELIVERED("delivered"),
    NO_CHANGE("no_change"),
    SKIPPED("skipped"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this != RUNNING
    val isSuccess: Boolean get() = this == DELIVERED || this == NO_CHANGE
    val countsAsFailure: Boolean get() = this == FAILED || this == TIMEOUT

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskRunStatus =
            entries.firstOrNull { it.wireValue == raw } ?: FAILED
    }
}

/** 触发来源。 */
@Serializable
enum class ScheduledTaskTriggerSource(val wireValue: String) {
    SCHEDULE("schedule"),
    MANUAL("manual"),
    MISFIRE_CATCHUP("misfire_catchup");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskTriggerSource =
            entries.firstOrNull { it.wireValue == raw } ?: SCHEDULE
    }
}

/** 定时任务领域模型。 */
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean = true,
    val schedule: ScheduledTaskSchedule = ScheduledTaskSchedule(),
    val misfirePolicy: ScheduledTaskMisfirePolicy = ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW,
    val misfireWindowMinutes: Int = DEFAULT_MISFIRE_WINDOW_MINUTES,
    val quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
    val deliveryMode: ScheduledTaskDeliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
    /** 执行期间是否展示前台运行进度通知与状态栏胶囊；关闭后具备后台豁免条件时静默运行，仅在完成时按 [deliveryMode] 推送结果。 */
    val showRunningNotification: Boolean = false,
    val sessionMode: ScheduledTaskSessionMode = ScheduledTaskSessionMode.ISOLATED,
    val dedicatedConversationKey: String? = null,
    /** null 表示跟随全局默认模型。 */
    val modelId: String? = null,
    /** null 表示跟随全局默认思考设置。 */
    val reasoningEffort: String? = null,
    val capabilities: ScheduledTaskCapabilities = ScheduledTaskCapabilities(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** 任务专属小记事本（游标/上次检查状态），防止重复播报并保持上下文精简。 */
    val memo: String = "",
    val memoUpdatedAt: Long = 0L,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val lastStatus: ScheduledTaskRunStatus? = null,
    val lastSummary: String = "",
    val consecutiveFailures: Int = 0,
    val autoDisabledReason: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = reasoningEffort?.takeIf { it.isNotBlank() }?.let(ReasoningEffort::fromWireValue)

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300
        const val MIN_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 1800
        const val DEFAULT_MISFIRE_WINDOW_MINUTES = 180
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_MEMO_CHARS = 16_000
    }
}

/** 单次定时任务运行记录。 */
data class ScheduledTaskRun(
    val id: String,
    val taskId: String,
    val triggerSource: ScheduledTaskTriggerSource,
    val scheduledForAt: Long?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: ScheduledTaskRunStatus,
    val summary: String = "",
    val notified: Boolean = false,
    val archiveConversationKey: String? = null,
    val modelLabel: String = "",
) {
    val durationMs: Long?
        get() = finishedAt?.let { (it - startedAt).coerceAtLeast(0L) }
}

/** 空状态精选灵感模板。 */
data class ScheduledTaskPreset(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @StringRes val defaultNameRes: Int,
    @StringRes val promptRes: Int,
    val schedule: ScheduledTaskSchedule,
    val deliveryMode: ScheduledTaskDeliveryMode,
    val sessionMode: ScheduledTaskSessionMode,
    val capabilities: ScheduledTaskCapabilities,
)

internal object ScheduledTaskPresets {
    val all: List<ScheduledTaskPreset> = listOf(
        ScheduledTaskPreset(
            id = "evening_briefing",
            titleRes = R.string.scheduled_preset_evening_briefing_title,
            subtitleRes = R.string.scheduled_preset_evening_briefing_subtitle,
            defaultNameRes = R.string.scheduled_preset_evening_briefing_name,
            promptRes = R.string.scheduled_preset_evening_briefing_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.DAILY,
                hour = 22,
                minute = 0,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = false,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "morning_digest",
            titleRes = R.string.scheduled_preset_morning_digest_title,
            subtitleRes = R.string.scheduled_preset_morning_digest_subtitle,
            defaultNameRes = R.string.scheduled_preset_morning_digest_name,
            promptRes = R.string.scheduled_preset_morning_digest_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.WEEKDAYS,
                hour = 8,
                minute = 0,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = true,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "web_watcher",
            titleRes = R.string.scheduled_preset_web_watcher_title,
            subtitleRes = R.string.scheduled_preset_web_watcher_subtitle,
            defaultNameRes = R.string.scheduled_preset_web_watcher_name,
            promptRes = R.string.scheduled_preset_web_watcher_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.INTERVAL,
                intervalMinutes = 120,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ON_CHANGE,
            sessionMode = ScheduledTaskSessionMode.ISOLATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = false,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = false,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "weekly_review",
            titleRes = R.string.scheduled_preset_weekly_review_title,
            subtitleRes = R.string.scheduled_preset_weekly_review_subtitle,
            defaultNameRes = R.string.scheduled_preset_weekly_review_name,
            promptRes = R.string.scheduled_preset_weekly_review_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.WEEKLY,
                hour = 21,
                minute = 0,
                daysOfWeek = setOf(7),
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = false,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = true,
                mcpTools = false,
                guiTools = false,
            ),
        ),
    )

    fun find(id: String?): ScheduledTaskPreset? = id?.let { target -> all.firstOrNull { it.id == target } }
}
