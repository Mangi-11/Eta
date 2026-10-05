package io.github.mangi.eta.agent.automation

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskRulesTest {
    @Test
    fun onceIsConsumedAndIntervalsSkipMissedExecutions() {
        val once = AgentTaskRules.validate(
            JSONObject().put("type", "once").put("at", "2026-10-10T09:00:00+08:00")
        )
        assertEquals(
            Instant.parse("2026-10-10T01:00:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(once, 0, true)
        )
        assertNull(AgentTaskRules.nextTime(once, 0, false))
        val interval = AgentTaskRules.validate(
            JSONObject().put("type", "interval").put("seconds", 3600)
                .put("startAt", "2026-10-10T09:00:00+08:00")
        )
        assertEquals(
            Instant.parse("2026-10-10T04:00:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(
                interval,
                Instant.parse("2026-10-10T03:20:00Z").toEpochMilli(),
                false
            )
        )
    }

    @Test
    fun dailyUsesExplicitTimeZoneWeekdaysAndDstTransition() {
        val rule = AgentTaskRules.validate(
            JSONObject().put("type", "daily").put("time", "09:00").put("timeZone", "Asia/Shanghai")
                .put("weekdays", JSONArray(listOf(1, 2, 3, 4, 5)))
        )
        assertEquals(
            Instant.parse("2026-10-12T01:00:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(
                rule,
                Instant.parse("2026-10-09T02:00:00Z").toEpochMilli(),
                false
            )
        )
        val dst = AgentTaskRules.validate(
            JSONObject().put("type", "daily").put("time", "02:30")
                .put("timeZone", "America/New_York")
        )
        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(
                dst,
                Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
                false
            )
        )
    }

    @Test
    fun eventFiltersAndConditionsAreLocallyMatched() {
        val rule = AgentTaskRules.validate(
            JSONObject().put("type", "notification_posted")
                .put(
                    "filters",
                    JSONObject().put("packageName", "com.example.app")
                        .put("titleContains", "Delivery")
                )
                .put("conditions", JSONObject().put("charging", true).put("unlocked", false))
        )
        assertTrue(
            AgentTaskRules.matches(
                rule,
                "notification_posted",
                JSONObject().put("packageName", "com.example.app").put("title", "DELIVERY arrived")
            )
        )
        assertFalse(
            AgentTaskRules.matches(
                rule,
                "notification_removed",
                JSONObject().put("packageName", "com.example.app").put("title", "Delivery")
            )
        )
        assertTrue(
            AgentTaskRules.conditionsMatch(
                rule,
                mapOf("charging" to true, "unlocked" to false)
            )
        )
        assertFalse(
            AgentTaskRules.conditionsMatch(
                rule,
                mapOf("charging" to false, "unlocked" to false)
            )
        )
        assertFalse(AgentTaskRules.conditionsMatch(rule, emptyMap()))
    }

    @Test
    fun unsupportedTriggersUnknownFiltersAndFractionalIntervalsAreRejected() {
        val invalid = listOf(
            JSONObject().put("type", "location_enter"),
            JSONObject().put("type", "interval").put("seconds", 900.5),
            JSONObject().put("type", "app_foreground")
                .put("filters", JSONObject().put("textContains", "text")),
            JSONObject().put("type", "daily").put("time", "25:00").put("timeZone", "Asia/Shanghai"),
            JSONObject().put("type", "screen_off")
                .put("conditions", JSONObject().put("unlocked", "true"))
        )
        invalid.forEach { raw -> assertThrows(Exception::class.java) { AgentTaskRules.validate(raw) } }
    }
}
