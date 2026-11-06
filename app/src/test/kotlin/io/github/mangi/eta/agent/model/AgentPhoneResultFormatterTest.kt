package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPhoneResultFormatterTest {
    @Test
    fun alarmCreationRequiresVerificationAndIntentOnlyMeansSubmitted() {
        assertEquals("已创建闹钟 · 07:30", summarize("set_alarm", """
            {"ok":true,"verified":true,"alarm":{"hour":7,"minute":30,"label":"private-label"}}
        """))
        assertEquals("已提交闹钟请求 · 07:30", summarize("set_alarm", """
            {"ok":true,"status":"dispatched","verified":false,"hour":7,"minute":30}
        """))
        assertEquals("已提交计时请求 · 1 小时 1 分钟 1 秒", summarize("set_timer", """
            {"ok":true,"status":"dispatched","verified":false,"duration_seconds":3661}
        """))
        assertNull(summarize("set_alarm", """{"ok":true,"verified":false}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"mode":"ui_fallback"}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"status":"unconfirmed"}"""))
    }

    @Test
    fun missingOrInvalidClockValuesDoNotInventTimeOrDisabledState() {
        listOf("{}", """{"hour":24,"minute":0}""", """{"hour":7,"minute":60}""",
            """{"hour":7.5,"minute":30}""").forEach { alarm ->
            assertEquals("已创建闹钟", summarize("set_alarm", """{"ok":true,"verified":true,"alarm":$alarm}"""))
        }
        listOf(-1, 0, 86401).forEach { seconds ->
            assertEquals("已提交计时请求", summarize("set_timer", """
                {"ok":true,"status":"dispatched","duration_seconds":$seconds}
            """))
        }
        assertNull(summarize("set_alarm_enabled", """{"ok":true,"verified":true,"alarm":{}}"""))
        assertEquals("闹钟保持关闭 · 07:30", summarize("set_alarm_enabled", """
            {"ok":true,"verified":true,"before":{"enabled":false},"alarm":{"enabled":false,"hour":7,"minute":30}}
        """))
        assertEquals("闹钟时间：07:00 → 07:30 · 未启用", summarize("update_alarm_time", """
            {"ok":true,"verified":true,"before":{"hour":7,"minute":0},"alarm":{"hour":7,"minute":30,"enabled":false}}
        """))
    }

    @Test
    fun calendarSummaryNamesChangedFieldsWithoutExposingContents() {
        val result = summarize("update_calendar_event", """
            {"ok":true,"verified":true,"event_id":"private-id",
             "before":{"title":"private-old","description":"private-body","eventLocation":"private-place","dtstart":"1","dtend":"2","reminders":[{"minutes":"5","method":"1"}]},
             "event":{"title":"private-new","description":"private-body-new","eventLocation":"private-place-new","dtstart":"3","dtend":"4","reminders":[{"minutes":"10","method":"1"}]}}
        """)
        assertEquals("已更新日程 · 标题、时间、地点、说明、提醒", result)
        assertFalse(result!!.contains("private"))
        assertFalse(result.contains('\n'))
        assertEquals("日程未变化", summarize("update_calendar_event", """
            {"ok":true,"verified":true,
             "before":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"5","method":"1"},{"minutes":"10","method":"1"}]},
             "event":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"10","method":"1"},{"minutes":"5","method":"1"}]}}
        """))
        assertEquals("已更新日程", summarize("update_calendar_event", """{"ok":true,"verified":true}"""))
        assertEquals("已更新日程", summarize("update_calendar_event", """
            {"ok":true,"verified":true,"before":{"title":"private-title"},"event":{"title":"private-title"}}
        """))
    }

    @Test
    fun calendarCountsAreOnlyUsedForCreationAndNotesAreRecycled() {
        assertEquals("已创建 3 个日程", summarize("create_calendar_events", """{"ok":true,"verified":true,"count":3}"""))
        assertEquals("已删除日程", summarize("delete_calendar_event", """{"ok":true,"verified":true,"affected":8}"""))
        assertEquals("便签已移至回收站", summarize("delete_note", """
            {"ok":true,"verified":true,"mode":"recycle","affected":1,"before":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("已创建便签", summarize("create_note", """
            {"ok":true,"verified":true,"note":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("找到 2 个闹钟 · 还有更多", summarize("list_alarms", """
            {"ok":true,"items":[{"label":"private-one"},{"label":"private-two"}],"has_more":true}
        """))
        assertEquals("暂无闹钟", summarize("list_alarms", """{"ok":true,"items":[]}"""))
        assertEquals("找到 1 个可用日历", summarize("list_calendars", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("未找到可用日历", summarize("list_calendars", """{"ok":true,"items":[]}"""))
        assertEquals("找到 1 个计时器", summarize("list_active_timers", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("暂无活跃计时器", summarize("list_active_timers", """{"ok":true,"items":[]}"""))
    }

    @Test
    fun readsRequireReturnedObjects() {
        assertEquals("已读取便签", summarize("read_note", """{"ok":true,"note":{"content":"private-body"}}"""))
        assertEquals("已读取日程", summarize("read_calendar_event", """{"ok":true,"event":{"title":"private-title"}}"""))
        listOf("read_note", "read_calendar_event").forEach { tool ->
            assertNull(summarize(tool, """{"ok":true}"""))
            assertNull(summarize(tool, """{"ok":true,"note":null,"event":null}"""))
        }
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPhoneResultFormatter.summarize(toolName, JSONObject(json))
}
