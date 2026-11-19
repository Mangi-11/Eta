package io.github.mangi.eta.agent.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.GeneratedImageActivityPayload
import io.github.mangi.eta.agent.imagegen.ImageGenToolCatalog
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.core.AppStrings
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> AppStrings.get(R.string.trace_arg_web_search, "搜索网页")
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> AppStrings.get(R.string.trace_arg_run_command_android, "执行命令 · Android")
            "write_file" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_write_file, "写入文件"),
                toolCall.argumentsJson,
                "content",
            )
            "read_file" -> AppStrings.get(R.string.trace_arg_read_file, "读取文件")
            "list_directory" -> AppStrings.get(R.string.trace_arg_list_directory, "列出目录")
            "edit_file" -> AppStrings.get(R.string.trace_arg_edit_file, "精确编辑文件")
            "stat_file" -> AppStrings.get(R.string.trace_arg_stat_file, "查看文件信息")
            "glob_files" -> AppStrings.get(R.string.trace_arg_glob_files, "按路径查找文件")
            "grep_files" -> AppStrings.get(R.string.trace_arg_grep_files, "搜索文件内容")
            "inspect_app" -> AppStrings.get(R.string.trace_arg_inspect_app, "检查应用")
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_input_text, "输入文本"),
                toolCall.argumentsJson,
                "text",
            )
            "replace_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_replace_text, "替换文本"),
                toolCall.argumentsJson,
                "text",
            )
            "paste_text", "set_clipboard" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_paste_text, "粘贴文本"),
                toolCall.argumentsJson,
                "text",
            )
            "clear_text" -> AppStrings.get(R.string.trace_arg_clear_text, "清空文本")
            "get_clipboard" -> AppStrings.get(R.string.trace_arg_get_clipboard, "读取剪贴板")
            "search_apps" -> summarizeQueryArguments(
                AppStrings.get(R.string.trace_arg_search_apps, "搜索应用"),
                toolCall.argumentsJson,
            )
            "launch_app" -> AppStrings.get(R.string.trace_arg_launch_app, "打开应用")
            "present_to_user" -> AppStrings.get(R.string.trace_arg_present_to_user, "呈现到主屏")
            "get_current_context" -> AppStrings.get(R.string.trace_arg_get_current_context, "读取时间与位置")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments(
                AppStrings.get(R.string.trace_arg_tap, "点击屏幕"),
                toolCall.argumentsJson,
            )
            "long_press" -> summarizePointArguments(
                AppStrings.get(R.string.trace_arg_long_press, "长按屏幕"),
                toolCall.argumentsJson,
            )
            "tap_area" -> AppStrings.get(R.string.trace_arg_tap_area, "点击区域")
            "tap_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_arg_tap_element, "点击元素"),
                toolCall.argumentsJson,
            )
            "long_press_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_arg_long_press_element, "长按元素"),
                toolCall.argumentsJson,
            )
            "swipe" -> AppStrings.get(R.string.trace_arg_swipe, "滑动屏幕")
            "scroll" -> summarizeScrollArguments(
                AppStrings.get(R.string.trace_arg_scroll, "滚动屏幕"),
                toolCall.argumentsJson,
            )
            "scroll_element" -> summarizeScrollArguments(
                AppStrings.get(R.string.trace_arg_scroll_element, "滚动元素"),
                toolCall.argumentsJson,
                withIndex = true,
            )
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> AppStrings.get(R.string.trace_arg_wait_for_text, "等待文本出现")
            "wait_for_package" -> AppStrings.get(R.string.trace_arg_wait_for_package, "等待应用就绪")
            "open_system_panel" -> AppStrings.get(R.string.trace_arg_open_system_panel, "打开系统面板")
            "read_image" -> AppStrings.get(R.string.trace_arg_read_image, "查看图片")
            ImageGenToolCatalog.TOOL_NAME -> summarizeImageGenArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY ->
                AppStrings.get(R.string.trace_arg_read_history, "读取当前会话历史")
            "memory_get", "character_memory_get", "memory_write", "character_memory_write" ->
                AgentPersonalResultFormatter.summarizeArguments(toolCall.name, toolCall.argumentsJson)
                    ?: AppStrings.get(R.string.trace_arg_memory_fallback, "记忆")
            "skills_list" -> AppStrings.get(R.string.trace_arg_skills_list, "查看技能列表")
            "skills_read" -> AppStrings.get(R.string.trace_arg_skills_read, "读取技能")
            "skills_read_resource" -> AppStrings.get(R.string.trace_arg_skills_read_resource, "读取技能资源")
            "skills_list_curated" -> AppStrings.get(R.string.trace_arg_skills_list_curated, "浏览精选技能")
            "skills_inspect_github" -> AppStrings.get(R.string.trace_arg_skills_inspect_github, "查看技能详情")
            "skills_install_from_github" -> AppStrings.get(R.string.trace_arg_skills_install_from_github, "安装技能")
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]?.resolve()
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.title
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) {
                        "search_notes" -> AppStrings.get(R.string.trace_arg_search_notes, "查询便签")
                        "search_system_memories" ->
                            AppStrings.get(R.string.trace_arg_search_system_memories, "查询系统记忆")
                        "read_personal_item" ->
                            AppStrings.get(R.string.trace_arg_read_personal_item, "读取检索详情")
                        "summarize_bills" ->
                            AppStrings.get(R.string.trace_arg_summarize_bills, "汇总账单")
                        else -> null
                    }
                when {
                    label == null -> AppStrings.get(R.string.trace_arg_preparing, "准备执行")
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
            SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.encodeForWire()
        } else if (toolCall.name == ImageGenToolCatalog.TOOL_NAME) {
            GeneratedImageActivityPayload.initialFromArguments(toolCall.argumentsJson)?.encode()
        } else if (toolCall.name in AgentTerminalToolCatalog.names || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String {
        val redacted = AppStrings.get(R.string.trace_redacted, "<已隐藏>")
        return replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=$redacted"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}$redacted"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$redacted"
            }
    }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    private fun summarizeImageGenArguments(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val refs = args.optJSONArray("reference_images")?.length() ?: 0
            val count = args.optInt("count", 1).coerceIn(1, 4)
            val action = if (refs > 0) {
                AppStrings.get(R.string.trace_arg_edit_image, "编辑图片")
            } else {
                AppStrings.get(R.string.trace_arg_generate_image, "生成图片")
            }
            if (count > 1) {
                AppStrings.get(R.string.trace_arg_image_count, "%1\$s · %2\$d 张", action, count)
            } else {
                action
            }
        }.getOrDefault(AppStrings.get(R.string.trace_arg_generate_image, "生成图片"))

    fun summarizeOpenUriArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_open_uri, "交给外部应用")
        return runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull(label, scheme, host).joinToString(" · ")
        }.getOrDefault(label)
    }

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { AppStrings.get(R.string.trace_arg_browser_action, "浏览器操作") }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) {
            AppStrings.get(R.string.trace_arg_fetch_continue, "继续读取网页")
        } else {
            AppStrings.get(R.string.trace_arg_fetch_url, "读取网页")
        }
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault(AppStrings.get(R.string.trace_arg_fetch_url, "读取网页"))

    private fun summarizeTerminalArguments(toolName: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = terminalActionLabel(toolName, arguments.optString("action", if (toolName == "terminal") "exec" else ""))
            val hasEnvironment = !arguments.has("session_id") && (
                toolName == "terminal" && arguments.optString("action", "exec") in setOf("open", "exec", "open_and_exec") ||
                    arguments.optString("action") in setOf("start", "daemon_start"))
            val environment = if (hasEnvironment) arguments.optString("environment", "android").terminalEnvironmentLabel() else null
            val identity = arguments.optString("identity")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add(terminalLabel(toolName))
                add(action)
                environment?.let(::add)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) {
                    add(AppStrings.get(R.string.trace_arg_terminal_async, "后台"))
                }
            }.joinToString(" · ")
        }.getOrDefault(AppStrings.get(R.string.trace_terminal_default, "终端"))

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            val countText = AppStrings.quantity(
                R.plurals.trace_arg_char_count,
                chars,
                "%1\$d 字符",
                "%1\$d 字符",
                chars,
            )
            "$label · $countText"
        }.getOrDefault(label)

    /** 只展示动作与长度，不回显正文：输入内容可能是密码或私人消息。 */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> AppStrings.get(R.string.trace_arg_clear_text, "清空文本")
                args.optString("mode") == "append" -> AppStrings.get(R.string.trace_arg_append_text, "追加文本")
                else -> AppStrings.get(R.string.trace_arg_input_text, "输入文本")
            }
            buildString {
                append(label)
                if (chars > 0) {
                    val countText = AppStrings.quantity(
                        R.plurals.trace_arg_char_count,
                        chars,
                        "%1\$d 字符",
                        "%1\$d 字符",
                        chars,
                    )
                    append(" · ").append(countText)
                }
                if (args.optBoolean("submit")) {
                    append(" · ").append(AppStrings.get(R.string.trace_arg_submit, "提交"))
                }
            }
        }.getOrDefault(AppStrings.get(R.string.trace_arg_input_text, "输入文本"))

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_press_key, "按键")
        return runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull(label, button).joinToString(" · ")
        }.getOrDefault(label)
    }

    private fun summarizeWaitArguments(argumentsJson: String): String {
        val waitLabel = AppStrings.get(R.string.trace_arg_wait, "等待")
        return runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                val formatted = String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_unit_seconds_text, "%1\$s 秒", formatted)
            } else {
                AppStrings.get(R.string.trace_unit_ms, "%1\$d 毫秒", durationMs)
            }
            "$waitLabel · $duration"
        }.getOrDefault(waitLabel)
    }

    private fun summarizeObservationArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_observe_screen, "观察屏幕")
        return runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add(label)
                if (options.includeScreenshot) {
                    add(AppStrings.get(R.string.trace_arg_with_screenshot, "含截图"))
                }
                if (options.includeUiTree) {
                    add(AppStrings.get(R.string.trace_arg_with_ui_tree, "含界面树"))
                }
            }.joinToString(" · ")
        }.getOrDefault(label)
    }

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName in AgentTerminalToolCatalog.names || toolName == "run_command") {
            return summarizeTerminalResult(toolName, json)
        }
        if (json?.optString("status") == "unconfirmed") {
            return AppStrings.get(R.string.trace_res_unconfirmed, "操作已提交，效果未确认")
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentPersonalResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        val done = AppStrings.get(R.string.trace_res_done, "完成")
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult)
                ?: AppStrings.get(R.string.trace_res_browser_done, "浏览器操作完成")
            ImageGenToolCatalog.TOOL_NAME -> {
                val count = json?.optInt("count", 0) ?: 0
                if (count > 0) {
                    AppStrings.quantity(
                        R.plurals.trace_res_images_generated,
                        count,
                        "已生成 %1\$d 张图片",
                        "已生成 %1\$d 张图片",
                        count,
                    )
                } else {
                    AppStrings.get(R.string.trace_res_image_gen_done, "图片生成完成")
                }
            }
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: done
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: done
            AgentConversationToolCatalog.READ_HISTORY ->
                AppStrings.get(R.string.trace_res_history_read, "已读取历史分页")
            "observe_screen" -> if (result.images.isNotEmpty()) {
                AppStrings.get(R.string.trace_res_screen_captured, "已截取当前屏幕")
            } else {
                AppStrings.get(R.string.trace_res_ui_captured, "已获取当前界面")
            }
            "tap", "tap_element", "tap_area" -> AppStrings.get(R.string.trace_res_tapped, "已点击")
            "long_press", "long_press_element" -> AppStrings.get(R.string.trace_res_long_pressed, "已长按")
            "swipe" -> AppStrings.get(R.string.trace_res_swiped, "已滑动")
            "scroll", "scroll_element" -> AppStrings.get(R.string.trace_res_scrolled, "已滚动")
            "type_text", "input_text", "paste_text" ->
                AppStrings.get(R.string.trace_res_text_entered, "已输入文本")
            "clear_text" -> AppStrings.get(R.string.trace_res_text_cleared, "已清空文本")
            "press_key" -> AppStrings.get(R.string.trace_res_key_pressed, "已按键")
            "wait" -> AppStrings.get(R.string.trace_res_wait_done, "等待完成")
            "wait_for_text" -> AppStrings.get(R.string.trace_res_text_appeared, "已出现目标文本")
            "wait_for_package" -> AppStrings.get(R.string.trace_res_app_ready, "应用已就绪")
            "inspect_app" -> AppStrings.get(R.string.trace_res_app_inspected, "已获取应用信息")
            "get_clipboard" -> AppStrings.get(R.string.trace_res_clipboard_read, "已读取剪贴板")
            "set_clipboard" -> AppStrings.get(R.string.trace_res_clipboard_copied, "已复制到剪贴板")
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: done
            "launch_app" -> json?.let(::summarizeLaunchAppResult)
                ?: AppStrings.get(R.string.trace_res_opened, "已打开")
            "present_to_user" -> json?.let(::summarizePresentToUserResult)
                ?: AppStrings.get(R.string.trace_res_presented, "已呈现到主屏")
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: done
            else -> json?.let { summarizeGenericResult(it, result) } ?: done
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留计数。动作与主机已在参数摘要中，
     * 结果只给出结论，避免工作过程同一行重复。
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val done = AppStrings.get(R.string.trace_res_done, "完成")
        if (json == null) return done
        if (!json.optBoolean("ok", true)) {
            val failedLabel = if (toolName == "web_search") {
                AppStrings.get(R.string.trace_res_web_search_failed, "网页搜索失败")
            } else {
                AppStrings.get(R.string.trace_res_web_fetch_failed, "网页读取失败")
            }
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull(failedLabel, code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> done
                0 -> AppStrings.get(R.string.trace_res_no_results, "未找到相关结果")
                else -> AppStrings.quantity(
                    R.plurals.trace_res_web_search_found,
                    count,
                    "找到 %1\$d 条搜索结果",
                    "找到 %1\$d 条搜索结果",
                    count,
                )
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return done
        val formatted = formatCharCount(chars)
        return if (json.optBoolean("has_more")) {
            AppStrings.get(R.string.trace_res_read_prefix, "已读前 %1\$s", formatted)
        } else {
            AppStrings.get(R.string.trace_res_read_amount, "已读 %1\$s", formatted)
        }
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add(AppStrings.get(R.string.trace_res_failed, "失败"))
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeSubagentArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val resumeId = sanitizeSummaryValue(args.optString("resume"), 32)
        val subagentType = sanitizeSummaryValue(args.optString("subagent_type"), 32)
            .lowercase()
            .ifBlank { if (resumeId.isNotBlank()) "subagent" else SubagentProfile.DEFAULT_TYPE }
        val description = sanitizeSummaryValue(args.optString("description"), MAX_QUERY_SUMMARY_CHARS)
        listOfNotNull(
            subagentType,
            description.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Subagent" }
    }.getOrDefault("Subagent")

    private fun summarizeSubagentResult(json: JSONObject): String {
        val toolCalls = json.optInt("tool_calls", 0)
        return if (toolCalls > 0) {
            AppStrings.quantity(
                R.plurals.trace_res_subagent_tool_calls,
                toolCalls,
                "完成 · %1\$d 次工具调用",
                "完成 · %1\$d 次工具调用",
                toolCalls,
            )
        } else {
            AppStrings.get(R.string.trace_res_done, "完成")
        }
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> AppStrings.get(R.string.trace_arg_subagent_save, "保存 Subagent")
            "delete" -> AppStrings.get(R.string.trace_arg_subagent_delete, "删除 Subagent")
            "get" -> AppStrings.get(R.string.trace_arg_subagent_get, "读取 Subagent")
            else -> AppStrings.get(R.string.trace_arg_subagent_list, "查看 Subagent 列表")
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault(AppStrings.get(R.string.trace_arg_subagent_manage, "管理 Subagent"))

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull(
                AppStrings.get(R.string.trace_res_saved, "已保存"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "delete" -> listOfNotNull(
                AppStrings.get(R.string.trace_res_deleted, "已删除"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "list" -> {
                val count = json.optJSONArray("profiles")?.length() ?: 0
                AppStrings.quantity(
                    R.plurals.trace_res_subagent_count,
                    count,
                    "%1\$d 个 Subagent",
                    "%1\$d 个 Subagent",
                    count,
                )
            }
            else -> AppStrings.get(R.string.trace_res_done, "完成")
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            val count = installed.length()
            val base = AppStrings.quantity(
                R.plurals.trace_res_skills_installed,
                count,
                "已安装 %1\$d 个技能",
                "已安装 %1\$d 个技能",
                count,
            )
            return if (json.optString("available") == "next_turn") {
                "$base · ${AppStrings.get(R.string.trace_res_available_next_turn, "下一轮可用")}"
            } else {
                base
            }
        }
        val items = json.optJSONArray("items") ?: return null
        val total = items.length()
        val installed = (0 until total).count { items.optJSONObject(it)?.opt("installed") == true }
        val base = AppStrings.quantity(
            R.plurals.trace_res_skills_found,
            total,
            "找到 %1\$d 个技能",
            "找到 %1\$d 个技能",
            total,
        )
        return if (installed > 0) {
            val installedText = AppStrings.quantity(
                R.plurals.trace_res_skills_already_installed,
                installed,
                "%1\$d 个已安装",
                "%1\$d 个已安装",
                installed,
            )
            "$base · $installedText"
        } else {
            base
        }
    }

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            if (json.has("verified") && !json.optBoolean("verified")) {
                add(AppStrings.get(R.string.trace_res_executed_unconfirmed, "已执行，效果未确认"))
            }
            val items = json.optJSONArray("items") ?: json.optJSONArray("results")
            val matches = json.optJSONArray("matches")
            val entries = json.optJSONArray("entries")
            val apps = json.optJSONArray("apps")
            val candidates = json.optJSONArray("candidates")

            when {
                items != null -> {
                    val count = items.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_results, "未找到相关结果"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_results,
                                count,
                                "找到 %1\$d 条结果",
                                "找到 %1\$d 条结果",
                                count,
                            )
                        )
                    }
                }
                matches != null -> {
                    val count = matches.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_matches, "未找到匹配内容"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_matches,
                                count,
                                "找到 %1\$d 处匹配",
                                "找到 %1\$d 处匹配",
                                count,
                            )
                        )
                    }
                }
                entries != null -> {
                    val count = entries.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_entries, "暂无内容"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_fetched_entries,
                                count,
                                "已获取 %1\$d 项内容",
                                "已获取 %1\$d 项内容",
                                count,
                            )
                        )
                    }
                }
                apps != null -> {
                    val count = apps.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_matching_apps, "未找到匹配应用"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_apps,
                                count,
                                "找到 %1\$d 个应用",
                                "找到 %1\$d 个应用",
                                count,
                            )
                        )
                    }
                }
                candidates != null -> {
                    val count = candidates.length()
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_candidates,
                            count,
                            "%1\$d 个候选",
                            "%1\$d 个候选",
                            count,
                        )
                    )
                }
                json.has("bytes_read") -> {
                    val bytes = json.optLong("bytes_read")
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_bytes_read,
                            bytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                            "已读 %1\$d 字节",
                            "已读 %1\$d 字节",
                            bytes,
                        )
                    )
                }
                json.has("replacements") -> {
                    val count = json.optInt("replacements")
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_replacements,
                            count,
                            "已替换 %1\$d 处",
                            "已替换 %1\$d 处",
                            count,
                        )
                    )
                }
                result.images.isNotEmpty() -> {
                    val count = result.images.size
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_image_count,
                            count,
                            "%1\$d 张图片",
                            "%1\$d 张图片",
                            count,
                        )
                    )
                }
                isEmpty() -> add(AppStrings.get(R.string.trace_res_done, "完成"))
            }
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) {
                add(AppStrings.get(R.string.trace_res_has_more, "还有更多"))
            }
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                add(AppStrings.get(R.string.trace_res_partial_scope, "采集范围有限"))
            }
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val noApps = AppStrings.get(R.string.trace_res_no_matching_apps, "未找到匹配应用")
        val apps = json.optJSONArray("apps") ?: return noApps
        val total = apps.length()
        if (total == 0) return noApps
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        val header = AppStrings.quantity(
            R.plurals.trace_res_search_apps_found,
            total,
            "已找到 %1\$d 个应用",
            "已找到 %1\$d 个应用",
            total,
        )
        val shown = names.take(MAX_LISTED_APP_NAMES)
        if (shown.isEmpty()) return header
        val separator = AppStrings.get(R.string.trace_list_separator, "、")
        val joined = shown.joinToString(separator)
        val namesText = if (total > shown.size) {
            AppStrings.get(R.string.trace_list_with_more, "%1\$s 等", joined)
        } else {
            joined
        }
        return "$header · $namesText"
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        val virtual = json.optString("canvas") == "virtual"
        val action = if (json.optString("launch_status") == "unconfirmed") {
            AppStrings.get(R.string.trace_res_launch_unconfirmed, "已请求打开，待确认")
        } else {
            AppStrings.get(R.string.trace_res_opened, "已打开")
        }
        val base = if (appName.isNotBlank()) "$action · $appName" else action
        return if (virtual) {
            "$base · ${AppStrings.get(R.string.trace_res_virtual_display, "虚拟屏")}"
        } else {
            base
        }
    }

    private fun summarizePresentToUserResult(json: JSONObject): String {
        val label = AppStrings.get(R.string.trace_res_presented, "已呈现到主屏")
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "$label · $appName" else label
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(toolName: String, json: JSONObject?): String {
        if (json == null) return terminalLabel(toolName)
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = terminalActionLabel(toolName, json.optString("action"))
            return if (json.optBoolean("ok", true)) {
                "${terminalLabel(toolName)} · $action"
            } else {
                "${AppStrings.get(R.string.trace_res_failed, "失败")} · $action"
            }
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> AppStrings.get(R.string.trace_res_terminal_timeout, "失败 · 执行超时")
            exitCode == 0 -> AppStrings.get(R.string.trace_res_terminal_done, "执行完成")
            else -> AppStrings.get(R.string.trace_res_terminal_exit_code, "失败 · 退出码 %1\$d", exitCode)
        }
        val output = if (exitCode == 0) {
            json.optString("stdout", json.optString("output"))
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout", json.optString("output")) }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add(AppStrings.get(R.string.trace_browser_title, "《%1\$s》", it)) }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let {
                    add(AppStrings.get(R.string.trace_browser_approx_chars, "约 %1\$s", formatCharCount(it)))
                }
            }
            elementCount?.let {
                add(
                    AppStrings.quantity(
                        R.plurals.trace_browser_elements,
                        it,
                        "%1\$d 个元素",
                        "%1\$d 个元素",
                        it,
                    )
                )
            }
            if (json.optBoolean("truncated", false)) {
                add(AppStrings.get(R.string.trace_res_truncated, "已截断"))
            }
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String {
        if (chars >= 10_000) {
            val unit = AppStrings.get(R.string.trace_char_count_large_unit, "万字")
            return if (unit == "k") {
                val thousands = String.format(Locale.US, "%.1f", chars / 1_000f).trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_char_count_thousands, "%1\$sk 字", thousands)
            } else {
                val tenThousands = String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_char_count_ten_thousands, "%1\$s 万字", tenThousands)
            }
        }
        return AppStrings.quantity(R.plurals.trace_char_count, chars, "%1\$d 字", "%1\$d 字", chars)
    }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> AppStrings.get(R.string.trace_browser_act_navigate, "打开网页")
        "get_readable" -> AppStrings.get(R.string.trace_browser_act_get_readable, "提取正文")
        "get_text" -> AppStrings.get(R.string.trace_browser_act_get_text, "读取文本")
        "find_elements" -> AppStrings.get(R.string.trace_browser_act_find_elements, "查找元素")
        "click" -> AppStrings.get(R.string.trace_browser_act_click, "点击网页")
        "type" -> AppStrings.get(R.string.trace_browser_act_type, "输入内容")
        "scroll" -> AppStrings.get(R.string.trace_browser_act_scroll, "滚动网页")
        "screenshot" -> AppStrings.get(R.string.trace_browser_act_screenshot, "网页截图")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_act_get_page_info, "查看网页信息")
        "go_back" -> AppStrings.get(R.string.trace_browser_act_go_back, "网页后退")
        "go_forward" -> AppStrings.get(R.string.trace_browser_act_go_forward, "网页前进")
        "reload" -> AppStrings.get(R.string.trace_browser_act_reload, "刷新网页")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_act_wait_for_selector, "等待网页元素")
        else -> AppStrings.get(R.string.trace_arg_browser_action, "浏览器操作")
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> AppStrings.get(R.string.trace_browser_res_navigate, "已打开")
        "get_readable" -> AppStrings.get(R.string.trace_browser_res_get_readable, "已提取正文")
        "get_text" -> AppStrings.get(R.string.trace_browser_res_get_text, "已读取文本")
        "find_elements" -> AppStrings.get(R.string.trace_browser_res_find_elements, "已找到元素")
        "click" -> AppStrings.get(R.string.trace_browser_res_click, "已点击网页")
        "type" -> AppStrings.get(R.string.trace_browser_res_type, "已输入内容")
        "scroll" -> AppStrings.get(R.string.trace_browser_res_scroll, "已滚动网页")
        "screenshot" -> AppStrings.get(R.string.trace_browser_res_screenshot, "已截图")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_res_get_page_info, "已读取页面信息")
        "go_back" -> AppStrings.get(R.string.trace_browser_res_go_back, "已后退")
        "go_forward" -> AppStrings.get(R.string.trace_browser_res_go_forward, "已前进")
        "reload" -> AppStrings.get(R.string.trace_browser_res_reload, "已刷新")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_res_wait_for_selector, "已等到目标元素")
        else -> AppStrings.get(R.string.trace_res_browser_done, "浏览器操作完成")
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> AppStrings.get(R.string.trace_dir_up, "向上")
        "down" -> AppStrings.get(R.string.trace_dir_down, "向下")
        "left" -> AppStrings.get(R.string.trace_dir_left, "向左")
        "right" -> AppStrings.get(R.string.trace_dir_right, "向右")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> AppStrings.get(R.string.trace_key_back, "返回")
        "home" -> AppStrings.get(R.string.trace_key_home, "主页")
        "recents", "recent" -> AppStrings.get(R.string.trace_key_recents, "最近任务")
        "notifications" -> AppStrings.get(R.string.trace_key_notifications, "通知栏")
        "quick_settings" -> AppStrings.get(R.string.trace_key_quick_settings, "控制中心")
        "power" -> AppStrings.get(R.string.trace_key_power, "电源")
        "volume_up" -> AppStrings.get(R.string.trace_key_volume_up, "音量加")
        "volume_down" -> AppStrings.get(R.string.trace_key_volume_down, "音量减")
        "mute" -> AppStrings.get(R.string.trace_key_mute, "静音")
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> AppStrings.get(R.string.trace_terminal_job, "异步命令")
        "terminal_daemon" -> AppStrings.get(R.string.trace_terminal_daemon, "后台服务")
        else -> AppStrings.get(R.string.trace_terminal_default, "终端")
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_terminal_job_start, "启动命令")
            "list" -> AppStrings.get(R.string.trace_terminal_job_list, "任务列表")
            "read" -> AppStrings.get(R.string.trace_terminal_job_read, "读取输出")
            "wait" -> AppStrings.get(R.string.trace_terminal_job_wait, "等待完成")
            "stop" -> AppStrings.get(R.string.trace_terminal_job_stop, "停止命令")
            else -> AppStrings.get(R.string.trace_terminal_job_other, "任务操作")
        }
        "terminal_daemon" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_terminal_daemon_start, "启动服务")
            "list" -> AppStrings.get(R.string.trace_terminal_daemon_list, "服务列表")
            "logs" -> AppStrings.get(R.string.trace_terminal_daemon_logs, "查看日志")
            "stop" -> AppStrings.get(R.string.trace_terminal_daemon_stop, "停止服务")
            else -> AppStrings.get(R.string.trace_terminal_daemon_other, "服务操作")
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> AppStrings.get(R.string.trace_terminal_act_check_root, "检测 Root 授权")
        "open" -> AppStrings.get(R.string.trace_terminal_act_open, "创建会话")
        "exec" -> AppStrings.get(R.string.trace_terminal_act_exec, "执行命令")
        "open_and_exec" -> AppStrings.get(R.string.trace_terminal_act_open_and_exec, "单次执行")
        "read_async_result" -> AppStrings.get(R.string.trace_terminal_act_read_async, "读取后台输出")
        "close" -> AppStrings.get(R.string.trace_terminal_act_close, "关闭终端")
        "daemon_start" -> AppStrings.get(R.string.trace_terminal_act_daemon_start, "启动守护任务")
        "daemon_list" -> AppStrings.get(R.string.trace_terminal_act_daemon_list, "守护任务列表")
        "daemon_logs" -> AppStrings.get(R.string.trace_terminal_act_daemon_logs, "查看守护日志")
        "daemon_stop" -> AppStrings.get(R.string.trace_terminal_act_daemon_stop, "停止守护任务")
        else -> AppStrings.get(R.string.trace_terminal_act_other, "终端操作")
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private data class LocalizedLabel(
        @StringRes val resId: Int,
        val fallback: String,
    ) {
        fun resolve(): String = AppStrings.get(resId, fallback)
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 10
        const val MAX_TERMINAL_PREVIEW_CHARS = 800
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to LocalizedLabel(R.string.trace_act_set_alarm, "设置闹钟"),
            "set_timer" to LocalizedLabel(R.string.trace_act_set_timer, "设置计时器"),
            "device_status" to LocalizedLabel(R.string.trace_act_device_status, "查看设备状态"),
            "network_info" to LocalizedLabel(R.string.trace_act_network_info, "查看网络信息"),
            "top_memory_apps" to LocalizedLabel(R.string.trace_act_top_memory_apps, "查看内存占用排行"),
            "top_storage_apps" to LocalizedLabel(R.string.trace_act_top_storage_apps, "查看存储占用排行"),
            "media_control" to LocalizedLabel(R.string.trace_act_media_control, "控制媒体播放"),
            "set_volume" to LocalizedLabel(R.string.trace_act_set_volume, "调整音量"),
            "get_setting" to LocalizedLabel(R.string.trace_act_get_setting, "读取系统设置"),
            "wifi_credentials" to LocalizedLabel(R.string.trace_act_wifi_credentials, "读取 Wi-Fi 密码"),
            "recent_notifications" to LocalizedLabel(R.string.trace_act_recent_notifications, "读取最近通知"),
            "search_notification_history" to LocalizedLabel(R.string.trace_act_search_notification_history, "搜索通知历史"),
            "recent_app_activity" to LocalizedLabel(R.string.trace_act_recent_app_activity, "查看应用活动"),
            "app_usage_summary" to LocalizedLabel(R.string.trace_act_app_usage_summary, "查看应用使用统计"),
            "get_current_location" to LocalizedLabel(R.string.trace_act_get_current_location, "获取当前位置"),
            "get_device_environment" to LocalizedLabel(R.string.trace_act_get_device_environment, "查看设备环境"),
            "list_alarms" to LocalizedLabel(R.string.trace_act_list_alarms, "查看闹钟列表"),
            "list_active_timers" to LocalizedLabel(R.string.trace_act_list_active_timers, "查看计时器"),
            "search_clipboard_history" to LocalizedLabel(R.string.trace_act_search_clipboard_history, "搜索剪贴板历史"),
            "get_health_summary" to LocalizedLabel(R.string.trace_act_get_health_summary, "查看健康摘要"),
            "read_sms_code" to LocalizedLabel(R.string.trace_act_read_sms_code, "读取短信验证码"),
            "get_logcat" to LocalizedLabel(R.string.trace_act_get_logcat, "读取系统日志"),
            "search_media" to LocalizedLabel(R.string.trace_act_search_media, "搜索媒体文件"),
            "search_audio" to LocalizedLabel(R.string.trace_act_search_audio, "搜索音频"),
            "search_recordings" to LocalizedLabel(R.string.trace_act_search_recordings, "搜索录音"),
            "search_files" to LocalizedLabel(R.string.trace_act_search_files, "搜索文件"),
            "search_calendar_events" to LocalizedLabel(R.string.trace_act_search_calendar_events, "搜索日程"),
            "search_contacts" to LocalizedLabel(R.string.trace_act_search_contacts, "搜索联系人"),
            "search_call_history" to LocalizedLabel(R.string.trace_act_search_call_history, "搜索通话记录"),
            "search_messages" to LocalizedLabel(R.string.trace_act_search_messages, "搜索短信"),
            "search_downloads" to LocalizedLabel(R.string.trace_act_search_downloads, "搜索下载内容"),
            "search_coloros_notes" to LocalizedLabel(R.string.trace_act_search_coloros_notes, "搜索便签"),
            "search_coloros_recordings" to LocalizedLabel(R.string.trace_act_search_coloros_recordings, "搜索录音机"),
            "search_recording_summaries" to LocalizedLabel(R.string.trace_act_search_recording_summaries, "搜索录音摘要"),
            "search_coloros_memories" to LocalizedLabel(R.string.trace_act_search_coloros_memories, "搜索小布记忆"),
            "search_saved_places" to LocalizedLabel(R.string.trace_act_search_saved_places, "搜索收藏地点"),
            "search_personal_orders" to LocalizedLabel(R.string.trace_act_search_personal_orders, "搜索个人订单"),
            "search_qq_chat_images" to LocalizedLabel(R.string.trace_act_search_qq_chat_images, "搜索 QQ 聊天图片"),
            "search_wechat_chat_images" to LocalizedLabel(R.string.trace_act_search_wechat_chat_images, "搜索微信聊天图片"),
            "set_setting" to LocalizedLabel(R.string.trace_act_set_setting, "修改系统设置"),
            "set_device_state" to LocalizedLabel(R.string.trace_act_set_device_state, "修改设备状态"),
            "app_state_control" to LocalizedLabel(R.string.trace_act_app_state_control, "管理应用状态"),
        )
    }
}
