package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskQuietHours
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 定时任务排期、Cron 解析、安静时段与漏跑窗口的纯函数计算引擎。
 *
 * 所有计算显式接收 [nowMs] 与 [defaultZone]，保证在不同时区、夏令时切换与单元测试中确定性一致。
 */
internal object ScheduledTaskScheduleCalculator {
    const val MIN_INTERVAL_MINUTES = 5
    const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    private const val CRON_MAX_SEARCH_DAYS = 366 * 4

    data class CronExpression(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>,
        val domWildcard: Boolean,
        val dowWildcard: Boolean,
    ) {
        fun matches(time: ZonedDateTime): Boolean {
            if (time.minute !in minutes) return false
            if (time.hour !in hours) return false
            if (time.monthValue !in months) return false
            val domMatch = time.dayOfMonth in daysOfMonth
            // ISO DayOfWeek: 1=Mon..7=Sun -> Vixie cron: 0=Sun, 1=Mon..6=Sat
            val cronDow = if (time.dayOfWeek == DayOfWeek.SUNDAY) 0 else time.dayOfWeek.value
            val dowMatch = cronDow in daysOfWeek
            return when {
                domWildcard && dowWildcard -> true
                domWildcard -> dowMatch
                dowWildcard -> domMatch
                // 标准 Vixie-cron 语义：dom 与 dow 均非通配时为 OR 关系。
                else -> domMatch || dowMatch
            }
        }
    }

    sealed interface MisfireDecision {
        data object RunNow : MisfireDecision
        data object RunCatchUp : MisfireDecision
        data class Skip(val reason: String) : MisfireDecision
    }

    fun resolveZone(schedule: ScheduledTaskSchedule, defaultZone: ZoneId = ZoneId.systemDefault()): ZoneId {
        val raw = schedule.timezoneId.trim()
        if (raw.isEmpty()) return defaultZone
        return runCatching { ZoneId.of(raw) }.getOrElse { defaultZone }
    }

    /** 计算晚于 [afterMs] 的下一次合法触发时间；无后续触发（如已过期一次性任务或无效 Cron）返回 null。 */
    fun computeNextRunAt(
        schedule: ScheduledTaskSchedule,
        afterMs: Long,
        quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val zone = resolveZone(schedule, defaultZone)
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs ?: return null
                target.takeIf { it > afterMs }
            }
            ScheduledTaskTriggerKind.DAILY -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                findNextCalendarOccurrence(afterMs, zone, quietHours) { date ->
                    date.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                }
            }
            ScheduledTaskTriggerKind.WEEKDAYS -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                val weekdays = setOf(1, 2, 3, 4, 5)
                findNextWeeklyOccurrence(afterMs, zone, hour, minute, weekdays, quietHours)
            }
            ScheduledTaskTriggerKind.WEEKLY -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                val days = schedule.daysOfWeek.filter { it in 1..7 }.toSet().ifEmpty { setOf(1) }
                findNextWeeklyOccurrence(afterMs, zone, hour, minute, days, quietHours)
            }
            ScheduledTaskTriggerKind.INTERVAL -> {
                val intervalMinutes = schedule.intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
                val stepMs = intervalMinutes * 60_000L
                var candidate = afterMs + stepMs
                // 若命中安静时段，则按步长顺延出安静时段，避免无意义空转。
                if (quietHours.enabled) {
                    var guard = 0
                    while (guard < 288 && isInQuietHours(candidate, quietHours, zone)) {
                        candidate += stepMs
                        guard++
                    }
                    if (isInQuietHours(candidate, quietHours, zone)) return null
                }
                candidate
            }
            ScheduledTaskTriggerKind.CRON -> {
                val parsed = parseCron(schedule.cronExpression) ?: return null
                findNextCronOccurrence(parsed, afterMs, zone, quietHours)
            }
        }
    }

    /** 校验排期配置是否有效，返回面向用户的本地化错误提示或 null。 */
    fun validateSchedule(
        schedule: ScheduledTaskSchedule,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (schedule.timezoneId.isNotBlank() && runCatching { ZoneId.of(schedule.timezoneId.trim()) }.isFailure) {
            return AppStrings.get(
                R.string.scheduled_error_invalid_timezone,
                "时区标识无效：%1\$s",
                schedule.timezoneId,
            )
        }
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs
                when {
                    target == null -> AppStrings.get(
                        R.string.scheduled_error_once_time_required,
                        "请设置一次性任务的触发时间",
                    )
                    target <= nowMs -> AppStrings.get(
                        R.string.scheduled_error_once_time_in_past,
                        "一次性任务的触发时间必须晚于当前时间",
                    )
                    else -> null
                }
            }
            ScheduledTaskTriggerKind.DAILY,
            ScheduledTaskTriggerKind.WEEKDAYS -> {
                if (schedule.hour !in 0..23 || schedule.minute !in 0..59) {
                    AppStrings.get(R.string.scheduled_error_time_out_of_range, "时间超出有效范围")
                } else null
            }
            ScheduledTaskTriggerKind.WEEKLY -> {
                when {
                    schedule.hour !in 0..23 || schedule.minute !in 0..59 ->
                        AppStrings.get(R.string.scheduled_error_time_out_of_range, "时间超出有效范围")
                    schedule.daysOfWeek.none { it in 1..7 } ->
                        AppStrings.get(R.string.scheduled_error_weekly_days_required, "请至少选择一个重复星期")
                    else -> null
                }
            }
            ScheduledTaskTriggerKind.INTERVAL -> {
                if (schedule.intervalMinutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
                    AppStrings.get(
                        R.string.scheduled_error_interval_out_of_range,
                        "固定间隔需在 %1\$d 分钟到 7 天之间",
                        MIN_INTERVAL_MINUTES,
                    )
                } else null
            }
            ScheduledTaskTriggerKind.CRON -> {
                val parsed = parseCron(schedule.cronExpression)
                when {
                    parsed == null -> AppStrings.get(
                        R.string.scheduled_error_cron_invalid,
                        "Cron 表达式无效，需为 5 段格式（分 时 日 月 周），例如 0 9 * * 1-5",
                    )
                    findNextCronOccurrence(parsed, nowMs, resolveZone(schedule, defaultZone)) == null ->
                        AppStrings.get(
                            R.string.scheduled_error_cron_no_future_run,
                            "该 Cron 表达式在未来 4 年内没有可触发的时间",
                        )
                    else -> null
                }
            }
        }
    }

    /** 判断到期任务是准点运行、窗口内补跑还是跳过。 */
    fun evaluateMisfire(
        task: ScheduledTask,
        scheduledForAt: Long,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): MisfireDecision {
        val zone = resolveZone(task.schedule, defaultZone)
        if (task.schedule.kind != ScheduledTaskTriggerKind.ONCE &&
            isInQuietHours(nowMs, task.quietHours, zone)
        ) {
            return MisfireDecision.Skip(
                AppStrings.get(R.string.scheduled_skip_quiet_hours, "当前处于免打扰时段，已跳过本次执行"),
            )
        }
        val delayMs = (nowMs - scheduledForAt).coerceAtLeast(0L)
        // 2 分钟以内视为正常系统抖动（Android Doze / 精确闹钟唤醒耗时），按准点执行处理。
        if (delayMs <= 2 * 60_000L) return MisfireDecision.RunNow
        return when (task.misfirePolicy) {
            ScheduledTaskMisfirePolicy.SKIP -> MisfireDecision.Skip(
                AppStrings.get(R.string.scheduled_skip_misfire_policy, "已错过预定触发时间，按策略跳过"),
            )
            ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW -> {
                val windowMs = task.misfireWindowMinutes.coerceIn(5, 24 * 60) * 60_000L
                if (delayMs <= windowMs) {
                    MisfireDecision.RunCatchUp
                } else {
                    MisfireDecision.Skip(
                        AppStrings.get(
                            R.string.scheduled_skip_misfire_window_expired,
                            "错过触发时间超过补跑窗口，已跳过",
                        ),
                    )
                }
            }
        }
    }

    fun isInQuietHours(
        epochMs: Long,
        quietHours: ScheduledTaskQuietHours,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!quietHours.enabled) return false
        val start = quietHours.startHour.coerceIn(0, 23) * 60 + quietHours.startMinute.coerceIn(0, 59)
        val end = quietHours.endHour.coerceIn(0, 23) * 60 + quietHours.endMinute.coerceIn(0, 59)
        if (start == end) return false
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        val current = local.hour * 60 + local.minute
        return if (start < end) {
            current in start until end
        } else {
            // 跨午夜窗口，如 23:00 ~ 07:30
            current >= start || current < end
        }
    }

    /** 解析标准 5 字段 Vixie Cron 表达式：minute hour day-of-month month day-of-week。 */
    fun parseCron(expression: String): CronExpression? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null
        val minutes = parseField(fields[0], 0, 59) ?: return null
        val hours = parseField(fields[1], 0, 23) ?: return null
        val dom = parseField(fields[2], 1, 31) ?: return null
        val months = parseField(fields[3], 1, 12) ?: return null
        val dowRaw = parseField(fields[4], 0, 7) ?: return null
        val dow = dowRaw.mapTo(linkedSetOf()) { if (it == 7) 0 else it }
        return CronExpression(
            minutes = minutes,
            hours = hours,
            daysOfMonth = dom,
            months = months,
            daysOfWeek = dow,
            domWildcard = fields[2] == "*",
            dowWildcard = fields[4] == "*",
        )
    }

    /** 生成简洁的排期摘要，供卡片、确认话术和通知展示。 */
    fun describeSchedule(
        schedule: ScheduledTaskSchedule,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timeText = formatHourMinute(schedule.hour, schedule.minute)
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs
                if (target == null) {
                    AppStrings.get(R.string.scheduled_schedule_once_unset, "一次性任务")
                } else {
                    val zone = resolveZone(schedule, defaultZone)
                    val zdt = Instant.ofEpochMilli(target).atZone(zone)
                    formatMonthDayTime(zdt)
                }
            }
            ScheduledTaskTriggerKind.DAILY ->
                AppStrings.get(R.string.scheduled_schedule_daily, "每天 %1\$s", timeText)
            ScheduledTaskTriggerKind.WEEKDAYS ->
                AppStrings.get(R.string.scheduled_schedule_weekdays, "工作日 %1\$s", timeText)
            ScheduledTaskTriggerKind.WEEKLY -> {
                val days = schedule.daysOfWeek.filter { it in 1..7 }.sorted()
                when {
                    days.size == 7 -> AppStrings.get(R.string.scheduled_schedule_daily, "每天 %1\$s", timeText)
                    days == listOf(1, 2, 3, 4, 5) -> AppStrings.get(R.string.scheduled_schedule_weekdays, "工作日 %1\$s", timeText)
                    days == listOf(6, 7) -> AppStrings.get(R.string.scheduled_schedule_weekends, "周末 %1\$s", timeText)
                    days.isEmpty() -> AppStrings.get(R.string.scheduled_schedule_weekly_generic, "每周 %1\$s", timeText)
                    else -> {
                        val separator = AppStrings.get(R.string.scheduled_day_list_separator, "、")
                        val dayList = days.joinToString(separator, transform = ::shortDayName)
                        AppStrings.get(R.string.scheduled_schedule_weekly_days, "每%1\$s %2\$s", dayList, timeText)
                    }
                }
            }
            ScheduledTaskTriggerKind.INTERVAL -> formatInterval(schedule.intervalMinutes)
            ScheduledTaskTriggerKind.CRON -> {
                val expr = schedule.cronExpression.trim().ifBlank {
                    AppStrings.get(R.string.scheduled_cron_unset, "未配置")
                }
                AppStrings.get(R.string.scheduled_schedule_cron, "Cron · %1\$s", expr)
            }
        }
    }

    /** 将下一次执行时间格式化为易读的相对+绝对文本，如 “今天 22:00” / “明天 08:00” / “30 分钟后”。 */
    fun formatNextRunLabel(
        nextRunAt: Long?,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (nextRunAt == null) {
            return AppStrings.get(R.string.scheduled_next_run_unscheduled, "未排期")
        }
        val diffMs = nextRunAt - nowMs
        if (diffMs <= 60_000L) {
            return AppStrings.get(R.string.scheduled_next_run_soon, "即将运行")
        }
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(defaultZone)
        val targetZdt = Instant.ofEpochMilli(nextRunAt).atZone(defaultZone)
        val hhmm = targetZdt.format(HH_MM_FORMATTER)
        val dayDiff = ChronoUnit.DAYS.between(nowZdt.toLocalDate(), targetZdt.toLocalDate())
        return when (dayDiff) {
            0L -> {
                val minutes = diffMs / 60_000L
                if (minutes < 60L) {
                    AppStrings.quantity(
                        R.plurals.scheduled_next_run_in_minutes,
                        minutes.toInt(),
                        "%1\$d 分钟后（%2\$s）",
                        "%1\$d 分钟后（%2\$s）",
                        minutes,
                        hhmm,
                    )
                } else {
                    AppStrings.get(R.string.scheduled_next_run_today, "今天 %1\$s", hhmm)
                }
            }
            1L -> AppStrings.get(R.string.scheduled_next_run_tomorrow, "明天 %1\$s", hhmm)
            2L -> AppStrings.get(R.string.scheduled_next_run_day_after_tomorrow, "后天 %1\$s", hhmm)
            else -> formatMonthDayTime(targetZdt)
        }
    }

    fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun formatInterval(minutes: Int): String {
        val clamped = minutes.coerceAtLeast(1)
        return when {
            clamped % (24 * 60) == 0 -> {
                val days = clamped / (24 * 60)
                AppStrings.quantity(
                    R.plurals.scheduled_interval_days,
                    days,
                    "每 %1\$d 天",
                    "每 %1\$d 天",
                    days,
                )
            }
            clamped % 60 == 0 -> {
                val hours = clamped / 60
                AppStrings.quantity(
                    R.plurals.scheduled_interval_hours,
                    hours,
                    "每 %1\$d 小时",
                    "每 %1\$d 小时",
                    hours,
                )
            }
            clamped > 60 -> AppStrings.get(
                R.string.scheduled_interval_hours_minutes,
                "每 %1\$d 小时 %2\$d 分钟",
                clamped / 60,
                clamped % 60,
            )
            else -> AppStrings.quantity(
                R.plurals.scheduled_interval_minutes,
                clamped,
                "每 %1\$d 分钟",
                "每 %1\$d 分钟",
                clamped,
            )
        }
    }

    private fun shortDayName(isoDay: Int): String = when (isoDay) {
        1 -> AppStrings.get(R.string.scheduled_weekday_short_mon, "周一")
        2 -> AppStrings.get(R.string.scheduled_weekday_short_tue, "周二")
        3 -> AppStrings.get(R.string.scheduled_weekday_short_wed, "周三")
        4 -> AppStrings.get(R.string.scheduled_weekday_short_thu, "周四")
        5 -> AppStrings.get(R.string.scheduled_weekday_short_fri, "周五")
        6 -> AppStrings.get(R.string.scheduled_weekday_short_sat, "周六")
        7 -> AppStrings.get(R.string.scheduled_weekday_short_sun, "周日")
        else -> ""
    }

    private fun formatMonthDayTime(zdt: ZonedDateTime): String =
        AppStrings.get(
            R.string.scheduled_date_time_short,
            "%1\$d月%2\$d日 %3\$s",
            zdt.monthValue,
            zdt.dayOfMonth,
            zdt.format(HH_MM_FORMATTER),
        )

    private fun findNextCalendarOccurrence(
        afterMs: Long,
        zone: ZoneId,
        quietHours: ScheduledTaskQuietHours,
        projector: (ZonedDateTime) -> ZonedDateTime,
    ): Long? {
        val base = Instant.ofEpochMilli(afterMs).atZone(zone)
        for (offsetDays in 0..366) {
            val candidate = projector(base.plusDays(offsetDays.toLong()))
            val epochMs = candidate.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
        }
        return null
    }

    private fun findNextWeeklyOccurrence(
        afterMs: Long,
        zone: ZoneId,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        quietHours: ScheduledTaskQuietHours,
    ): Long? {
        val base = Instant.ofEpochMilli(afterMs).atZone(zone)
        for (offsetDays in 0..366) {
            val day = base.plusDays(offsetDays.toLong())
            if (day.dayOfWeek.value !in daysOfWeek) continue
            val candidate = day.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val epochMs = candidate.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
        }
        return null
    }

    private fun findNextCronOccurrence(
        cron: CronExpression,
        afterMs: Long,
        zone: ZoneId,
        quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
    ): Long? {
        var cursor = Instant.ofEpochMilli(afterMs)
            .atZone(zone)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
        val limit = cursor.plusDays(CRON_MAX_SEARCH_DAYS.toLong())
        while (cursor.isBefore(limit)) {
            if (cursor.monthValue !in cron.months) {
                cursor = cursor.withDayOfMonth(1).withHour(0).withMinute(0).plusMonths(1)
                continue
            }
            val domMatch = cursor.dayOfMonth in cron.daysOfMonth
            val cronDow = if (cursor.dayOfWeek == DayOfWeek.SUNDAY) 0 else cursor.dayOfWeek.value
            val dowMatch = cronDow in cron.daysOfWeek
            val dayMatches = when {
                cron.domWildcard && cron.dowWildcard -> true
                cron.domWildcard -> dowMatch
                cron.dowWildcard -> domMatch
                else -> domMatch || dowMatch
            }
            if (!dayMatches) {
                cursor = cursor.withHour(0).withMinute(0).plusDays(1)
                continue
            }
            if (cursor.hour !in cron.hours) {
                cursor = cursor.withMinute(0).plusHours(1)
                continue
            }
            if (cursor.minute !in cron.minutes) {
                cursor = cursor.plusMinutes(1)
                continue
            }
            val epochMs = cursor.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
            cursor = cursor.plusMinutes(1)
        }
        return null
    }

    private fun parseField(raw: String, min: Int, max: Int): Set<Int>? {
        if (raw.isBlank()) return null
        val result = linkedSetOf<Int>()
        for (part in raw.split(',')) {
            if (part.isBlank()) return null
            val stepPieces = part.split('/')
            if (stepPieces.size > 2) return null
            val step = if (stepPieces.size == 2) {
                stepPieces[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            } else 1
            val rangePart = stepPieces[0]
            val (start, end) = when {
                rangePart == "*" -> min to max
                '-' in rangePart -> {
                    val bounds = rangePart.split('-')
                    if (bounds.size != 2) return null
                    val s = bounds[0].toIntOrNull() ?: return null
                    val e = bounds[1].toIntOrNull() ?: return null
                    if (s !in min..max || e !in min..max || s > e) return null
                    s to e
                }
                else -> {
                    val single = rangePart.toIntOrNull() ?: return null
                    if (single !in min..max) return null
                    if (stepPieces.size == 2) single to max else single to single
                }
            }
            var value = start
            while (value <= end) {
                result.add(value)
                value += step
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** 将 ISO-8601 或本地 `yyyy-MM-dd HH:mm` 解析为 epochMs，供模型工具使用。 */
    fun parseIsoOrLocalDateTime(raw: String, defaultZone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val normalized = text.replace(' ', 'T')
        runCatching {
            LocalDateTime.parse(normalized).atZone(defaultZone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }
}
