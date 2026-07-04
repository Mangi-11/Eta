package fuck.andes.ui.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ConversationTimeLabels {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val weekdayLabels = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun label(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (timestampMillis <= 0L) return "最近"

        val nowStart = startOfDay(nowMillis, locale, timeZone)
        val targetStart = startOfDay(timestampMillis, locale, timeZone)
        val dayDelta = ((nowStart - targetStart) / DAY_MS).toInt()

        return when {
            dayDelta <= 0 -> format("HH:mm", timestampMillis, locale, timeZone)
            dayDelta == 1 -> "昨天"
            dayDelta in 2..6 -> weekdayLabel(timestampMillis, locale, timeZone)
            sameYear(timestampMillis, nowMillis, locale, timeZone) ->
                format("M-d", timestampMillis, locale, timeZone)
            else -> format("yyyy-M-d", timestampMillis, locale, timeZone)
        }
    }

    private fun startOfDay(millis: Long, locale: Locale, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone, locale).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

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

    private fun weekdayLabel(millis: Long, locale: Locale, timeZone: TimeZone): String {
        val calendar = Calendar.getInstance(timeZone, locale).apply { timeInMillis = millis }
        return weekdayLabels[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun format(
        pattern: String,
        millis: Long,
        locale: Locale,
        timeZone: TimeZone,
    ): String =
        SimpleDateFormat(pattern, locale).also { it.timeZone = timeZone }.format(Date(millis))
}
