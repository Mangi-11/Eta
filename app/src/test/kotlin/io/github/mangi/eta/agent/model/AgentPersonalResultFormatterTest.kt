package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPersonalResultFormatterTest {
    @Test
    fun memoryGetPresentsNaturalQueryOrReadStatusWithoutRawLineOrByteNumbers() {
        val querySuccess = summarize("memory_get", """{"matched_lines":3,"line_count":500,"bytes":15000}""")
        assertEquals("找到 3 条相关记忆", querySuccess)
        assertFalse(querySuccess!!.contains("行"))
        assertFalse(querySuccess.contains("字节"))

        val queryEmpty = summarize("memory_get", """{"matched_lines":0,"line_count":500,"bytes":15000}""")
        assertEquals("未找到相关记忆", queryEmpty)

        val fullRead = summarize("memory_get", """{"line_count":200,"bytes":8000}""")
        assertEquals("已读取记忆", fullRead)
        assertFalse(fullRead!!.contains("行"))
        assertFalse(fullRead.contains("字节"))

        val partialRead = summarize("memory_get", """{"has_more":true,"line_count":200,"bytes":8000}""")
        assertEquals("已读取前段记忆", partialRead)

        // 剧情记忆
        val charQuery = summarize("character_memory_get", """{"matched_lines":2}""")
        assertEquals("找到 2 条相关剧情", charQuery)
        val charEmpty = summarize("character_memory_get", """{"matched_lines":0}""")
        assertEquals("未找到相关剧情", charEmpty)
        val charRead = summarize("character_memory_get", """{}""")
        assertEquals("已读取剧情记忆", charRead)
    }

    @Test
    fun memoryWritePresentsActionConclusionWithoutByteOrLineCounts() {
        assertEquals("已保存记忆", summarize("memory_write", """{"mode":"append","bytes":5000,"line_count":120}"""))
        assertEquals("已更新记忆", summarize("memory_write", """{"mode":"replace_range","bytes":5000,"line_count":120}"""))
        assertEquals("已清空记忆", summarize("memory_write", """{"mode":"clear"}"""))

        assertEquals("已保存剧情记忆", summarize("character_memory_write", """{"mode":"append"}"""))
        assertEquals("已更新剧情记忆", summarize("character_memory_write", """{"mode":"replace_range"}"""))
        assertEquals("已清空剧情记忆", summarize("character_memory_write", """{"mode":"clear"}"""))
    }

    @Test
    fun memoryArgumentsAreCleanAndConcise() {
        assertEquals("追加记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"append","content":"abc"}"""))
        assertEquals("更新记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"replace_range","content":"abc"}"""))
        assertEquals("清空记忆", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"clear"}"""))
        assertEquals("检索记忆", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{"query":"咖啡偏好"}"""))
        assertEquals("读取记忆", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{}"""))

        assertEquals("追加剧情记忆", AgentPersonalResultFormatter.summarizeArguments("character_memory_write", """{"mode":"append"}"""))
        assertEquals("检索剧情记忆", AgentPersonalResultFormatter.summarizeArguments("character_memory_get", """{"query":"身世"}"""))
    }

    @Test
    fun personalSearchesPresentHumanReadableUnitsAndEmptyStates() {
        val cases = listOf(
            Triple("search_system_memories", "找到 3 条系统记忆", "未找到相关系统记忆"),
            Triple("search_notes", "找到 3 条便签", "未找到相关便签"),
            Triple("search_calendar_events", "找到 3 个日程", "未找到相关日程"),
            Triple("search_contacts", "找到 3 位联系人", "未找到匹配联系人"),
            Triple("search_messages", "找到 3 条短信", "未找到相关短信"),
            Triple("search_call_history", "找到 3 条通话记录", "未找到通话记录"),
            Triple("search_saved_places", "找到 3 个地点", "未找到相关地点"),
            Triple("search_personal_orders", "找到 3 个订单", "未找到相关订单"),
            Triple("search_bills", "找到 3 笔账单", "未找到账单记录"),
            Triple("search_todos", "找到 3 条待办", "未找到待办"),
            Triple("search_flights", "找到 3 趟航班", "未找到航班行程"),
            Triple("search_hotels", "找到 3 个酒店预订", "未找到预订记录"),
            Triple("search_trains", "找到 3 趟火车", "未找到火车行程"),
            Triple("search_daily_events", "找到 3 个生活事件", "未找到生活事件"),
            Triple("search_memory_collections", "找到 3 个记忆合集", "未找到记忆合集"),
            Triple("search_recordings", "找到 3 条录音", "未找到录音"),
            Triple("search_recording_summaries", "找到 3 条录音摘要", "未找到录音摘要"),
            Triple("recent_notifications", "找到 3 条通知", "未找到通知"),
            Triple("search_clipboard_history", "找到 3 条剪贴板记录", "未找到剪贴板记录"),
            Triple("search_downloads", "找到 3 个下载记录", "未找到下载记录"),
            Triple("search_media", "找到 3 个媒体文件", "未找到相关媒体文件"),
            Triple("search_qq_chat_images", "找到 3 张聊天图片", "未找到聊天图片"),
        )

        cases.forEach { (tool, expectedFound, expectedEmpty) ->
            val found = summarize(tool, """{"items":[{},{},{}]}""")
            assertEquals("Tool $tool found summary mismatch", expectedFound, found)
            val empty = summarize(tool, """{"items":[]}""")
            assertEquals("Tool $tool empty summary mismatch", expectedEmpty, empty)
        }
    }

    @Test
    fun personalSearchWithPaginationAndPartialScopePreservesIndicators() {
        val result = summarize("search_messages", """{"items":[{},{}],"has_more":true,"partial":true}""")
        assertEquals("找到 2 条短信 · 还有更多 · 采集范围有限", result)
    }

    @Test
    fun verificationCodeAndSpecialTools() {
        assertEquals("已读取验证码", summarize("read_sms_code", """{"items":[{"code":"123456"}]}"""))
        assertEquals("未收到最新验证码", summarize("read_sms_code", """{"items":[]}"""))
        assertEquals("已读取详情", summarize("read_personal_item", """{"id":"1"}"""))
        assertEquals("已汇总账单", summarize("summarize_bills", """{"total":100}"""))
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPersonalResultFormatter.summarize(toolName, JSONObject(json).also {
            if (!it.has("ok")) it.put("ok", true)
        })
}
