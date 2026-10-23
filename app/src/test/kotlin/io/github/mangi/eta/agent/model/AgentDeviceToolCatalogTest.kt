package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceToolCatalogTest {
    @Test
    fun riskGroupsExposeOnlyTheirOwnTools() {
        val none = names(false, false, false)
        val direct = names(true, false, false)
        val reads = names(false, true, false)
        val actions = names(false, false, true)

        assertFalse("set_alarm" in none)
        assertTrue("set_alarm" in direct)
        assertTrue("inspect_app" in direct)
        assertFalse("inspect_app" in reads)
        assertFalse("get_logcat" in direct)
        assertTrue("get_logcat" in reads)
        assertFalse("read_sms_code" in direct)
        assertTrue("read_sms_code" in reads)
        assertTrue("search_notes" in reads)
        assertTrue("search_coloros_recordings" in reads)
        assertTrue("search_recording_summaries" in reads)
        assertTrue("search_system_memories" in reads)
        assertTrue("search_notification_history" in reads)
        assertTrue("recent_app_activity" in reads)
        assertTrue("app_usage_summary" in reads)
        assertTrue("get_current_location" in reads)
        assertTrue("get_device_environment" in reads)
        assertTrue("list_alarms" in reads)
        assertTrue("list_active_timers" in reads)
        assertTrue("search_clipboard_history" in reads)
        assertTrue("get_health_summary" in reads)
        assertTrue("search_saved_places" in reads)
        assertTrue("search_personal_orders" in reads)
        assertTrue("search_qq_chat_images" in reads)
        assertTrue("search_wechat_chat_images" in reads)
        assertFalse("read_image" in reads)
        assertFalse("search_messages" in direct)
        assertFalse("send_message" in reads)
        assertFalse("send_message" in actions)
        assertTrue("app_state_control" in actions)
        assertTrue("create_calendar_events" in actions)
        assertFalse("create_calendar_events" in reads)
        assertTrue("search_bills" in reads)
        assertFalse("personal_context" in reads)
        assertFalse("search_coloros_notes" in reads)
        assertFalse("search_coloros_memories" in reads)
    }

    @Test
    fun setDeviceStateOmitsDisplayAutomationTargets() {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = true,
        )
        val enum = (0 until tools.length())
            .map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "set_device_state" }
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("target")
            .getJSONArray("enum")
        val names = (0 until enum.length()).map { enum.getString(it) }.toSet()
        assertFalse("auto_rotate" in names)
        assertFalse("auto_brightness" in names)
        assertEquals(
            setOf(
                "wifi",
                "bluetooth",
                "mobile_data",
                "airplane_mode",
                "location",
                "nfc",
                "battery_saver",
                "dark_mode",
                "night_light",
            ),
            names,
        )
    }

    private fun names(
        direct: Boolean,
        reads: Boolean,
        actions: Boolean,
    ): Set<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = direct,
            deviceSensitiveReadTools = reads,
            deviceSensitiveActionTools = actions,
        )
        return tools.toolNames()
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
