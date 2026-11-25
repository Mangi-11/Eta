package io.github.mangi.eta.ui.state

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit

internal object ConversationTimeLabels {
    fun label(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
        use24HourClock: Boolean = true,
        yesterdayLabel: String = "Yesterday",
        recentLabel: String = "Recent",
    ): String {
        if (timestampMillis <= 0L) return recentLabel

        val zoneId = timeZone.toZoneId()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val targetDate = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
        val dayDelta = ChronoUnit.DAYS.between(targetDate, nowDate).toInt()

        return when {
            dayDelta <= 0 -> formatPattern(
                if (use24HourClock) "HH:mm" else "h:mm a",
                timestampMillis,
                locale,
                timeZone,
            )
            dayDelta == 1 -> yesterdayLabel
            dayDelta in 2..6 -> formatPattern("EEE", timestampMillis, locale, timeZone)
            sameYear(timestampMillis, nowMillis, locale, timeZone) ->
                formatPattern(sameYearPattern(locale), timestampMillis, locale, timeZone)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                .also { it.timeZone = timeZone }
                .format(Date(timestampMillis))
        }
    }

    private fun sameYear(
        timestampMillis: Long,
        nowMillis: Long,
        locale: Locale,
        timeZone: TimeZone,
    ): Boolean {
        val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val target = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timestampMillis }
        return now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    }

    private fun formatPattern(
        pattern: String,
        millis: Long,
        locale: Locale,
        timeZone: TimeZone,
    ): String =
        SimpleDateFormat(pattern, locale).also { it.timeZone = timeZone }.format(Date(millis))

    private fun sameYearPattern(locale: Locale): String = when (locale.language) {
        Locale.CHINESE.language -> "M月d日"
        Locale.JAPANESE.language -> "M月d日"
        Locale.KOREAN.language -> "M월 d일"
        else -> "MMM d"
    }
}
