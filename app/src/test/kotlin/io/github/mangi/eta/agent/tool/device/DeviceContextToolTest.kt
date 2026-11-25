package io.github.mangi.eta.agent.tool.device

import io.github.mangi.eta.agent.device.DeviceLocationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceContextToolTest {
    @Test
    fun returnsCompactTimeAndLocationContext() {
        val result = JSONObject(
            DeviceContextTool.current(
                clock = Clock.fixed(
                    Instant.parse("2026-07-10T04:05:06.789Z"),
                    ZoneId.of("Asia/Shanghai"),
                ),
                location = DeviceLocationProvider.Result.Available(
                    latitude = 31.230416,
                    longitude = 121.473701,
                    accuracyMeters = 18.6f,
                    ageMillis = 42_900L,
                ),
            )
        )

        assertEquals(4, result.length())
        assertEquals("2026-07-10T12:05:06+08:00", result.getString("datetime"))
        assertEquals("Asia/Shanghai", result.getString("timezone"))
        assertEquals("星期五", result.getString("weekday"))
        result.getJSONObject("location").run {
            assertEquals(31.23042, getDouble("latitude"), 0.0)
            assertEquals(121.4737, getDouble("longitude"), 0.0)
            assertEquals(19, getInt("accuracy_m"))
            assertEquals(42L, getLong("age_s"))
        }
    }

    @Test
    fun keepsTimeAvailableWhenLocationIsUnavailable() {
        val result = JSONObject(
            DeviceContextTool.current(
                clock = Clock.fixed(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    ZoneId.of("Asia/Kathmandu"),
                ),
                location = DeviceLocationProvider.Result.Unavailable("permission_required"),
            )
        )

        assertEquals("2026-01-01T05:45:00+05:45", result.getString("datetime"))
        assertEquals("Asia/Kathmandu", result.getString("timezone"))
        result.getJSONObject("location").run {
            assertEquals("permission_required", getString("status"))
            assertFalse(has("latitude"))
        }
    }
}
