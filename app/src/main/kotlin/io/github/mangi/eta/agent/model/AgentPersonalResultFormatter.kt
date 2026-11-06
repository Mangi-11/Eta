package io.github.mangi.eta.agent.model

import org.json.JSONObject

/**
 * 个人数据检索与记忆工具结果摘要。
 * 面向用户展示自然流畅的结论，不暴露私密正文，不堆砌底层字节数或行数等机器指标。
 */
internal object AgentPersonalResultFormatter {
    fun summarizeArguments(toolName: String, argumentsJson: String): String? = runCatching {
        val args = JSONObject(argumentsJson)
        val isCharacter = toolName.startsWith("character_")
        val label = if (isCharacter) "剧情记忆" else "记忆"
        when (toolName) {
            "memory_get", "character_memory_get" -> {
                val query = args.optString("query").trim()
                if (query.isNotEmpty()) "检索$label" else "读取$label"
            }
            "memory_write", "character_memory_write" -> {
                when (args.optString("mode")) {
                    "append" -> "追加$label"
                    "clear" -> "清空$label"
                    else -> "更新$label"
                }
            }
            else -> null
        }
    }.getOrNull()

    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null

        when (toolName) {
            "memory_get", "character_memory_get" -> return summarizeMemoryGet(toolName, json)
            "memory_write", "character_memory_write" -> return summarizeMemoryWrite(toolName, json)
            "read_sms_code" -> {
                val count = itemCount(json)
                return if (count != null && count > 0) "已读取验证码" else "未收到最新验证码"
            }
            "read_personal_item" -> return "已读取详情"
            "summarize_bills" -> return "已汇总账单"
        }

        val target = SEARCH_TARGETS[toolName] ?: return null
        val count = itemCount(json) ?: return null
        return if (count == 0) {
            target.emptyText
        } else {
            val base = "找到 $count ${target.unit}"
            val suffix = buildList {
                if (json.optBoolean("has_more") || json.optBoolean("truncated")) add("还有更多")
                if (json.optBoolean("partial") || json.optBoolean("scan_limited")) add("采集范围有限")
            }.joinToString(" · ")
            if (suffix.isNotEmpty()) "$base · $suffix" else base
        }
    }

    private fun summarizeMemoryGet(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val noun = if (isCharacter) "剧情" else "记忆"
        val matchedLines = json.optInt("matched_lines", -1)
        if (matchedLines >= 0) {
            return if (matchedLines > 0) "找到 $matchedLines 条相关$noun" else "未找到相关$noun"
        }
        val label = if (isCharacter) "剧情记忆" else "记忆"
        return if (json.optBoolean("has_more")) "已读取前段$label" else "已读取$label"
    }

    private fun summarizeMemoryWrite(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val noun = if (isCharacter) "剧情记忆" else "记忆"
        return when (json.optString("mode")) {
            "clear" -> "已清空$noun"
            "append" -> "已保存$noun"
            else -> "已更新$noun"
        }
    }

    private fun itemCount(json: JSONObject): Int? =
        (json.optJSONArray("items") ?: json.optJSONArray("results"))?.length()
            ?: (json.opt("count") as? Number)?.toInt()?.takeIf { it >= 0 }

    private data class SearchTarget(val unit: String, val emptyText: String)

    private val SEARCH_TARGETS = mapOf(
        "search_system_memories" to SearchTarget("条系统记忆", "未找到相关系统记忆"),
        "search_coloros_memories" to SearchTarget("条系统记忆", "未找到相关系统记忆"),
        "search_notes" to SearchTarget("条便签", "未找到相关便签"),
        "search_coloros_notes" to SearchTarget("条便签", "未找到相关便签"),
        "search_calendar_events" to SearchTarget("个日程", "未找到相关日程"),
        "search_contacts" to SearchTarget("位联系人", "未找到匹配联系人"),
        "search_messages" to SearchTarget("条短信", "未找到相关短信"),
        "search_call_history" to SearchTarget("条通话记录", "未找到通话记录"),
        "search_saved_places" to SearchTarget("个地点", "未找到相关地点"),
        "search_personal_orders" to SearchTarget("个订单", "未找到相关订单"),
        "search_bills" to SearchTarget("笔账单", "未找到账单记录"),
        "search_todos" to SearchTarget("条待办", "未找到待办"),
        "search_calendar_todos" to SearchTarget("条待办", "未找到待办"),
        "search_flights" to SearchTarget("趟航班", "未找到航班行程"),
        "search_hotels" to SearchTarget("个酒店预订", "未找到预订记录"),
        "search_trains" to SearchTarget("趟火车", "未找到火车行程"),
        "search_daily_events" to SearchTarget("个生活事件", "未找到生活事件"),
        "search_memory_collections" to SearchTarget("个记忆合集", "未找到记忆合集"),
        "search_recordings" to SearchTarget("条录音", "未找到录音"),
        "search_coloros_recordings" to SearchTarget("条录音", "未找到录音"),
        "search_recording_summaries" to SearchTarget("条录音摘要", "未找到录音摘要"),
        "recent_notifications" to SearchTarget("条通知", "未找到通知"),
        "search_notification_history" to SearchTarget("条通知", "未找到通知"),
        "search_clipboard_history" to SearchTarget("条剪贴板记录", "未找到剪贴板记录"),
        "search_downloads" to SearchTarget("个下载记录", "未找到下载记录"),
        "search_media" to SearchTarget("个媒体文件", "未找到相关媒体文件"),
        "search_audio" to SearchTarget("个音频文件", "未找到相关音频文件"),
        "search_files" to SearchTarget("个文件", "未找到相关文件"),
        "search_qq_chat_images" to SearchTarget("张聊天图片", "未找到聊天图片"),
        "search_wechat_chat_images" to SearchTarget("张聊天图片", "未找到聊天图片"),
    )
}
