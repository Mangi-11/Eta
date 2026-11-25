package io.github.mangi.eta.agent.tool.device

import android.content.Context
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt
import org.json.JSONObject

/** 按需提供手机当前采用的时间环境与最近系统位置。 */
internal object DeviceContextTool {
    fun current(
        context: Context,
        clock: Clock = Clock.systemDefaultZone(),
    ): String = current(clock, DeviceLocationProvider.latest(context))

    internal fun current(
        clock: Clock,
        location: DeviceLocationProvider.Result,
    ): String {
        val localTime = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
        return JSONObject()
            .put("datetime", localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("timezone", localTime.zone.id)
            .put(
                "weekday",
                localTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
            )
            .put("location", location.toJson())
            .toString()
    }

    private fun DeviceLocationProvider.Result.toJson(): JSONObject = when (this) {
        is DeviceLocationProvider.Result.Available -> JSONObject()
            .put("latitude", latitude.roundCoordinate())
            .put("longitude", longitude.roundCoordinate())
            .put("accuracy_m", accuracyMeters?.roundToInt())
            .put("age_s", ageMillis / 1_000L)

        is DeviceLocationProvider.Result.Unavailable -> JSONObject().put("status", status)
    }

    private fun Double.roundCoordinate(): Double =
        round(this * COORDINATE_SCALE) / COORDINATE_SCALE

    private const val COORDINATE_SCALE = 100_000.0
}
