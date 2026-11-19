package io.github.mangi.eta.agent.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject

/**
 * 个人数据检索与记忆工具结果摘要。
 * 面向用户展示自然流畅的结论，不暴露私密正文，不堆砌底层字节数或行数等机器指标。
 */
internal object AgentPersonalResultFormatter {
    fun summarizeArguments(toolName: String, argumentsJson: String): String? = runCatching {
        val args = JSONObject(argumentsJson)
        val isCharacter = toolName.startsWith("character_")
        when (toolName) {
            "memory_get", "character_memory_get" -> {
                val hasQuery = args.optString("query").trim().isNotEmpty()
                when {
                    isCharacter && hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_story_memory, "检索剧情记忆")
                    isCharacter ->
                        AppStrings.get(R.string.trace_personal_arg_read_story_memory, "读取剧情记忆")
                    hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_memory, "检索记忆")
                    else ->
                        AppStrings.get(R.string.trace_personal_arg_read_memory, "读取记忆")
                }
            }
            "memory_write", "character_memory_write" -> {
                when (args.optString("mode")) {
                    "append" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_append_story_memory, "追加剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_append_memory, "追加记忆")
                    }
                    "clear" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_clear_story_memory, "清空剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_clear_memory, "清空记忆")
                    }
                    else -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_update_story_memory, "更新剧情记忆")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_update_memory, "更新记忆")
                    }
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
                return if (count != null && count > 0) {
                    AppStrings.get(R.string.trace_personal_sms_code_read, "已读取验证码")
                } else {
                    AppStrings.get(R.string.trace_personal_sms_code_empty, "未收到最新验证码")
                }
            }
            "read_personal_item" -> return AppStrings.get(R.string.trace_personal_item_read, "已读取详情")
            "summarize_bills" -> return AppStrings.get(R.string.trace_personal_bills_summarized, "已汇总账单")
        }

        val target = SEARCH_TARGETS[toolName] ?: return null
        val count = itemCount(json) ?: return null
        return if (count == 0) {
            AppStrings.get(target.emptyRes, target.emptyFallback)
        } else {
            val base = AppStrings.quantity(
                target.foundPluralsRes,
                count,
                target.foundFallback,
                target.foundFallback,
                count,
            )
            val suffix = buildList {
                if (json.optBoolean("has_more") || json.optBoolean("truncated")) {
                    add(AppStrings.get(R.string.trace_res_has_more, "还有更多"))
                }
                if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                    add(AppStrings.get(R.string.trace_res_partial_scope, "采集范围有限"))
                }
            }.joinToString(" · ")
            if (suffix.isNotEmpty()) "$base · $suffix" else base
        }
    }

    private fun summarizeMemoryGet(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val matchedLines = json.optInt("matched_lines", -1)
        if (matchedLines >= 0) {
            return if (matchedLines > 0) {
                if (isCharacter) {
                    AppStrings.quantity(
                        R.plurals.trace_personal_story_found,
                        matchedLines,
                        "找到 %1\$d 条相关剧情",
                        "找到 %1\$d 条相关剧情",
                        matchedLines,
                    )
                } else {
                    AppStrings.quantity(
                        R.plurals.trace_personal_memory_found,
                        matchedLines,
                        "找到 %1\$d 条相关记忆",
                        "找到 %1\$d 条相关记忆",
                        matchedLines,
                    )
                }
            } else {
                if (isCharacter) {
                    AppStrings.get(R.string.trace_personal_story_empty, "未找到相关剧情")
                } else {
                    AppStrings.get(R.string.trace_personal_memory_empty, "未找到相关记忆")
                }
            }
        }
        val hasMore = json.optBoolean("has_more")
        return when {
            isCharacter && hasMore ->
                AppStrings.get(R.string.trace_personal_story_read_prefix, "已读取前段剧情记忆")
            isCharacter ->
                AppStrings.get(R.string.trace_personal_story_read, "已读取剧情记忆")
            hasMore ->
                AppStrings.get(R.string.trace_personal_memory_read_prefix, "已读取前段记忆")
            else ->
                AppStrings.get(R.string.trace_personal_memory_read, "已读取记忆")
        }
    }

    private fun summarizeMemoryWrite(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        return when (json.optString("mode")) {
            "clear" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_cleared, "已清空剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_cleared, "已清空记忆")
            }
            "append" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_saved, "已保存剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_saved, "已保存记忆")
            }
            else -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_updated, "已更新剧情记忆")
            } else {
                AppStrings.get(R.string.trace_personal_memory_updated, "已更新记忆")
            }
        }
    }

    private fun itemCount(json: JSONObject): Int? =
        (json.optJSONArray("items") ?: json.optJSONArray("results"))?.length()
            ?: (json.opt("count") as? Number)?.toInt()?.takeIf { it >= 0 }

    private data class SearchTarget(
        @PluralsRes val foundPluralsRes: Int,
        val foundFallback: String,
        @StringRes val emptyRes: Int,
        val emptyFallback: String,
    )

    private val SEARCH_TARGETS = mapOf(
        "search_system_memories" to SearchTarget(
            R.plurals.trace_personal_found_system_memories,
            "找到 %1\$d 条系统记忆",
            R.string.trace_personal_empty_system_memories,
            "未找到相关系统记忆",
        ),
        "search_coloros_memories" to SearchTarget(
            R.plurals.trace_personal_found_system_memories,
            "找到 %1\$d 条系统记忆",
            R.string.trace_personal_empty_system_memories,
            "未找到相关系统记忆",
        ),
        "search_notes" to SearchTarget(
            R.plurals.trace_personal_found_notes,
            "找到 %1\$d 条便签",
            R.string.trace_personal_empty_notes,
            "未找到相关便签",
        ),
        "search_coloros_notes" to SearchTarget(
            R.plurals.trace_personal_found_notes,
            "找到 %1\$d 条便签",
            R.string.trace_personal_empty_notes,
            "未找到相关便签",
        ),
        "search_calendar_events" to SearchTarget(
            R.plurals.trace_personal_found_calendar_events,
            "找到 %1\$d 个日程",
            R.string.trace_personal_empty_calendar_events,
            "未找到相关日程",
        ),
        "search_contacts" to SearchTarget(
            R.plurals.trace_personal_found_contacts,
            "找到 %1\$d 位联系人",
            R.string.trace_personal_empty_contacts,
            "未找到匹配联系人",
        ),
        "search_messages" to SearchTarget(
            R.plurals.trace_personal_found_messages,
            "找到 %1\$d 条短信",
            R.string.trace_personal_empty_messages,
            "未找到相关短信",
        ),
        "search_call_history" to SearchTarget(
            R.plurals.trace_personal_found_call_history,
            "找到 %1\$d 条通话记录",
            R.string.trace_personal_empty_call_history,
            "未找到通话记录",
        ),
        "search_saved_places" to SearchTarget(
            R.plurals.trace_personal_found_saved_places,
            "找到 %1\$d 个地点",
            R.string.trace_personal_empty_saved_places,
            "未找到相关地点",
        ),
        "search_personal_orders" to SearchTarget(
            R.plurals.trace_personal_found_personal_orders,
            "找到 %1\$d 个订单",
            R.string.trace_personal_empty_personal_orders,
            "未找到相关订单",
        ),
        "search_bills" to SearchTarget(
            R.plurals.trace_personal_found_bills,
            "找到 %1\$d 笔账单",
            R.string.trace_personal_empty_bills,
            "未找到账单记录",
        ),
        "search_todos" to SearchTarget(
            R.plurals.trace_personal_found_todos,
            "找到 %1\$d 条待办",
            R.string.trace_personal_empty_todos,
            "未找到待办",
        ),
        "search_calendar_todos" to SearchTarget(
            R.plurals.trace_personal_found_todos,
            "找到 %1\$d 条待办",
            R.string.trace_personal_empty_todos,
            "未找到待办",
        ),
        "search_flights" to SearchTarget(
            R.plurals.trace_personal_found_flights,
            "找到 %1\$d 趟航班",
            R.string.trace_personal_empty_flights,
            "未找到航班行程",
        ),
        "search_hotels" to SearchTarget(
            R.plurals.trace_personal_found_hotels,
            "找到 %1\$d 个酒店预订",
            R.string.trace_personal_empty_hotels,
            "未找到预订记录",
        ),
        "search_trains" to SearchTarget(
            R.plurals.trace_personal_found_trains,
            "找到 %1\$d 趟火车",
            R.string.trace_personal_empty_trains,
            "未找到火车行程",
        ),
        "search_daily_events" to SearchTarget(
            R.plurals.trace_personal_found_daily_events,
            "找到 %1\$d 个生活事件",
            R.string.trace_personal_empty_daily_events,
            "未找到生活事件",
        ),
        "search_memory_collections" to SearchTarget(
            R.plurals.trace_personal_found_memory_collections,
            "找到 %1\$d 个记忆合集",
            R.string.trace_personal_empty_memory_collections,
            "未找到记忆合集",
        ),
        "search_recordings" to SearchTarget(
            R.plurals.trace_personal_found_recordings,
            "找到 %1\$d 条录音",
            R.string.trace_personal_empty_recordings,
            "未找到录音",
        ),
        "search_coloros_recordings" to SearchTarget(
            R.plurals.trace_personal_found_recordings,
            "找到 %1\$d 条录音",
            R.string.trace_personal_empty_recordings,
            "未找到录音",
        ),
        "search_recording_summaries" to SearchTarget(
            R.plurals.trace_personal_found_recording_summaries,
            "找到 %1\$d 条录音摘要",
            R.string.trace_personal_empty_recording_summaries,
            "未找到录音摘要",
        ),
        "recent_notifications" to SearchTarget(
            R.plurals.trace_personal_found_notifications,
            "找到 %1\$d 条通知",
            R.string.trace_personal_empty_notifications,
            "未找到通知",
        ),
        "search_notification_history" to SearchTarget(
            R.plurals.trace_personal_found_notifications,
            "找到 %1\$d 条通知",
            R.string.trace_personal_empty_notifications,
            "未找到通知",
        ),
        "search_clipboard_history" to SearchTarget(
            R.plurals.trace_personal_found_clipboard,
            "找到 %1\$d 条剪贴板记录",
            R.string.trace_personal_empty_clipboard,
            "未找到剪贴板记录",
        ),
        "search_downloads" to SearchTarget(
            R.plurals.trace_personal_found_downloads,
            "找到 %1\$d 个下载记录",
            R.string.trace_personal_empty_downloads,
            "未找到下载记录",
        ),
        "search_media" to SearchTarget(
            R.plurals.trace_personal_found_media,
            "找到 %1\$d 个媒体文件",
            R.string.trace_personal_empty_media,
            "未找到相关媒体文件",
        ),
        "search_audio" to SearchTarget(
            R.plurals.trace_personal_found_audio,
            "找到 %1\$d 个音频文件",
            R.string.trace_personal_empty_audio,
            "未找到相关音频文件",
        ),
        "search_files" to SearchTarget(
            R.plurals.trace_personal_found_files,
            "找到 %1\$d 个文件",
            R.string.trace_personal_empty_files,
            "未找到相关文件",
        ),
        "search_qq_chat_images" to SearchTarget(
            R.plurals.trace_personal_found_chat_images,
            "找到 %1\$d 张聊天图片",
            R.string.trace_personal_empty_chat_images,
            "未找到聊天图片",
        ),
        "search_wechat_chat_images" to SearchTarget(
            R.plurals.trace_personal_found_chat_images,
            "找到 %1\$d 张聊天图片",
            R.string.trace_personal_empty_chat_images,
            "未找到聊天图片",
        ),
    )
}
