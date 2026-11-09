package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> "搜索网页"
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> "执行命令 · Android"
            "write_file" -> summarizeTextLength("写入文件", toolCall.argumentsJson, "content")
            "read_file" -> "读取文件"
            "list_directory" -> "列出目录"
            "edit_file" -> "精确编辑文件"
            "stat_file" -> "查看文件信息"
            "glob_files" -> "按路径查找文件"
            "grep_files" -> "搜索文件内容"
            "inspect_app" -> "检查应用"
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength("输入文本", toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength("替换文本", toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("粘贴文本", toolCall.argumentsJson, "text")
            "clear_text" -> "清空文本"
            "get_clipboard" -> "读取剪贴板"
            "search_apps" -> summarizeQueryArguments("搜索应用", toolCall.argumentsJson)
            "launch_app" -> "打开应用"
            "present_to_user" -> "呈现到主屏"
            "get_current_context" -> "读取时间与位置"
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments("点击屏幕", toolCall.argumentsJson)
            "long_press" -> summarizePointArguments("长按屏幕", toolCall.argumentsJson)
            "tap_area" -> "点击区域"
            "tap_element" -> summarizeElementArguments("点击元素", toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments("长按元素", toolCall.argumentsJson)
            "swipe" -> "滑动屏幕"
            "scroll" -> summarizeScrollArguments("滚动屏幕", toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments("滚动元素", toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> "等待文本出现"
            "wait_for_package" -> "等待应用就绪"
            "open_system_panel" -> "打开系统面板"
            "read_image" -> "查看图片"
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY -> "读取当前会话历史"
            "memory_get", "character_memory_get", "memory_write", "character_memory_write" ->
                AgentPersonalResultFormatter.summarizeArguments(toolCall.name, toolCall.argumentsJson) ?: "记忆"
            "skills_list" -> "查看技能列表"
            "skills_read" -> "读取技能"
            "skills_read_resource" -> "读取技能资源"
            "skills_list_curated" -> "浏览精选技能"
            "skills_inspect_github" -> "查看技能详情"
            "skills_install_from_github" -> "安装技能"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.title
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) { "search_notes" -> "查询便签"; "search_system_memories" -> "查询系统记忆"; "read_personal_item" -> "读取检索详情"; "summarize_bills" -> "汇总账单"; else -> null }
                when {
                    label == null -> "准备执行"
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
            SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.encode()
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

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<已隐藏>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<已隐藏>"
            }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("交给外部应用", scheme, host).joinToString(" · ")
        }.getOrDefault("交给外部应用")

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { "浏览器操作" }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) "继续读取网页" else "读取网页"
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault("读取网页")

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
                if (arguments.optBoolean("async", false)) add("后台")
            }.joinToString(" · ")
        }.getOrDefault("终端")

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · $chars 字符"
        }.getOrDefault(label)

    /** 只展示动作与长度，不回显正文：输入内容可能是密码或私人消息。 */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> "清空文本"
                args.optString("mode") == "append" -> "追加文本"
                else -> "输入文本"
            }
            buildString {
                append(label)
                if (chars > 0) append(" · $chars 字符")
                if (args.optBoolean("submit")) append(" · 提交")
            }
        }.getOrDefault("输入文本")

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

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull("按键", button).joinToString(" · ")
        }.getOrDefault("按键")

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + " 秒"
            } else {
                "$durationMs 毫秒"
            }
            "等待 · $duration"
        }.getOrDefault("等待")

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add("观察屏幕")
                if (options.includeScreenshot) add("含截图")
                if (options.includeUiTree) add("含界面树")
            }.joinToString(" · ")
        }.getOrDefault("观察屏幕")

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
        if (json?.optString("status") == "unconfirmed") return "操作已提交，效果未确认"
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentPersonalResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "浏览器操作完成"
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: "完成"
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: "完成"
            AgentConversationToolCatalog.READ_HISTORY -> "已读取历史分页"
            "observe_screen" -> if (result.images.isNotEmpty()) "已截取当前屏幕" else "已获取当前界面"
            "tap", "tap_element", "tap_area" -> "已点击"
            "long_press", "long_press_element" -> "已长按"
            "swipe" -> "已滑动"
            "scroll", "scroll_element" -> "已滚动"
            "type_text", "input_text", "paste_text" -> "已输入文本"
            "clear_text" -> "已清空文本"
            "press_key" -> "已按键"
            "wait" -> "等待完成"
            "wait_for_text" -> "已出现目标文本"
            "wait_for_package" -> "应用已就绪"
            "inspect_app" -> "已获取应用信息"
            "get_clipboard" -> "已读取剪贴板"
            "set_clipboard" -> "已复制到剪贴板"
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "完成"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "已打开"
            "present_to_user" -> json?.let(::summarizePresentToUserResult) ?: "已呈现到主屏"
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: "完成"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "完成"
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留计数。动作与主机已在参数摘要中，
     * 结果只给出结论，避免工作过程同一行重复。
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") "网页搜索" else "网页读取"
        if (json == null) return "完成"
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull("${label}失败", code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> "完成"
                0 -> "未找到相关结果"
                else -> "找到 $count 条搜索结果"
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return "完成"
        return if (json.optBoolean("has_more")) "已读前 ${formatCharCount(chars)}" else "已读 ${formatCharCount(chars)}"
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("失败")
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
        return if (toolCalls > 0) "完成 · $toolCalls 次工具调用" else "完成"
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> "保存 Subagent"
            "delete" -> "删除 Subagent"
            "get" -> "读取 Subagent"
            else -> "查看 Subagent 列表"
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault("管理 Subagent")

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull("已保存", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "delete" -> listOfNotNull("已删除", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "list" -> "${json.optJSONArray("profiles")?.length() ?: 0} 个 Subagent"
            else -> "完成"
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            return "已安装 ${installed.length()} 个技能" +
                if (json.optString("available") == "next_turn") " · 下一轮可用" else ""
        }
        val items = json.optJSONArray("items") ?: return null
        val installed = (0 until items.length()).count { items.optJSONObject(it)?.opt("installed") == true }
        return "找到 ${items.length()} 个技能" + if (installed > 0) " · $installed 个已安装" else ""
    }

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            if (json.has("verified") && !json.optBoolean("verified")) {
                add("已执行，效果未确认")
            }
            val items = json.optJSONArray("items") ?: json.optJSONArray("results")
            val matches = json.optJSONArray("matches")
            val entries = json.optJSONArray("entries")
            val apps = json.optJSONArray("apps")
            val candidates = json.optJSONArray("candidates")

            when {
                items != null -> {
                    if (items.length() == 0) add("未找到相关结果")
                    else add("找到 ${items.length()} 条结果")
                }
                matches != null -> {
                    if (matches.length() == 0) add("未找到匹配内容")
                    else add("找到 ${matches.length()} 处匹配")
                }
                entries != null -> {
                    if (entries.length() == 0) add("暂无内容")
                    else add("已获取 ${entries.length()} 项内容")
                }
                apps != null -> {
                    if (apps.length() == 0) add("未找到匹配应用")
                    else add("找到 ${apps.length()} 个应用")
                }
                candidates != null -> add("${candidates.length()} 个候选")
                json.has("bytes_read") -> add("已读 ${json.optLong("bytes_read")} 字节")
                json.has("replacements") -> add("已替换 ${json.optInt("replacements")} 处")
                result.images.isNotEmpty() -> add("${result.images.size} 张图片")
                isEmpty() -> add("完成")
            }
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) add("还有更多")
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) add("采集范围有限")
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "未找到匹配应用"
        val total = apps.length()
        if (total == 0) return "未找到匹配应用"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("已找到 $total 个应用")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString("、"))
                if (total > shown.size) append(" 等")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        val virtual = json.optString("canvas") == "virtual"
        val action = if (json.optString("launch_status") == "unconfirmed") "已请求打开，待确认" else "已打开"
        val base = if (appName.isNotBlank()) "$action · $appName" else action
        return if (virtual) "$base · 虚拟屏" else base
    }

    private fun summarizePresentToUserResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "已呈现到主屏 · $appName" else "已呈现到主屏"
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
            return if (json.optBoolean("ok", true)) "${terminalLabel(toolName)} · $action" else "失败 · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "失败 · 执行超时"
            exitCode == 0 -> "执行完成"
            else -> "失败 · 退出码 $exitCode"
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
            title?.let { add("《$it》") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("约 ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it 个元素") }
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + " 万字"
        } else {
            "$chars 字"
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
        "navigate" -> "打开网页"
        "get_readable" -> "提取正文"
        "get_text" -> "读取文本"
        "find_elements" -> "查找元素"
        "click" -> "点击网页"
        "type" -> "输入内容"
        "scroll" -> "滚动网页"
        "screenshot" -> "网页截图"
        "get_page_info" -> "查看网页信息"
        "go_back" -> "网页后退"
        "go_forward" -> "网页前进"
        "reload" -> "刷新网页"
        "wait_for_selector" -> "等待网页元素"
        else -> "浏览器操作"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "已打开"
        "get_readable" -> "已提取正文"
        "get_text" -> "已读取文本"
        "find_elements" -> "已找到元素"
        "click" -> "已点击网页"
        "type" -> "已输入内容"
        "scroll" -> "已滚动网页"
        "screenshot" -> "已截图"
        "get_page_info" -> "已读取页面信息"
        "go_back" -> "已后退"
        "go_forward" -> "已前进"
        "reload" -> "已刷新"
        "wait_for_selector" -> "已等到目标元素"
        else -> "浏览器操作完成"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> "返回"
        "home" -> "主页"
        "recents", "recent" -> "最近任务"
        "notifications" -> "通知栏"
        "quick_settings" -> "控制中心"
        "power" -> "电源"
        "volume_up" -> "音量加"
        "volume_down" -> "音量减"
        "mute" -> "静音"
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> "异步命令"
        "terminal_daemon" -> "后台服务"
        else -> "终端"
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> "启动命令"
            "list" -> "任务列表"
            "read" -> "读取输出"
            "wait" -> "等待完成"
            "stop" -> "停止命令"
            else -> "任务操作"
        }
        "terminal_daemon" -> when (action) {
            "start" -> "启动服务"
            "list" -> "服务列表"
            "logs" -> "查看日志"
            "stop" -> "停止服务"
            else -> "服务操作"
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> "检测 Root 授权"
        "open" -> "创建会话"
        "exec" -> "执行命令"
        "open_and_exec" -> "单次执行"
        "read_async_result" -> "读取后台输出"
        "close" -> "关闭终端"
        "daemon_start" -> "启动守护任务"
        "daemon_list" -> "守护任务列表"
        "daemon_logs" -> "查看守护日志"
        "daemon_stop" -> "停止守护任务"
        else -> "终端操作"
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
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
            "set_alarm" to "设置闹钟",
            "set_timer" to "设置计时器",
            "device_status" to "查看设备状态",
            "network_info" to "查看网络信息",
            "top_memory_apps" to "查看内存占用排行",
            "top_storage_apps" to "查看存储占用排行",
            "media_control" to "控制媒体播放",
            "set_volume" to "调整音量",
            "get_setting" to "读取系统设置",
            "wifi_credentials" to "读取 Wi-Fi 密码",
            "recent_notifications" to "读取最近通知",
            "search_notification_history" to "搜索通知历史",
            "recent_app_activity" to "查看应用活动",
            "app_usage_summary" to "查看应用使用统计",
            "get_current_location" to "获取当前位置",
            "get_device_environment" to "查看设备环境",
            "list_alarms" to "查看闹钟列表",
            "list_active_timers" to "查看计时器",
            "search_clipboard_history" to "搜索剪贴板历史",
            "get_health_summary" to "查看健康摘要",
            "read_sms_code" to "读取短信验证码",
            "get_logcat" to "读取系统日志",
            "search_media" to "搜索媒体文件",
            "search_audio" to "搜索音频",
            "search_recordings" to "搜索录音",
            "search_files" to "搜索文件",
            "search_calendar_events" to "搜索日程",
            "search_contacts" to "搜索联系人",
            "search_call_history" to "搜索通话记录",
            "search_messages" to "搜索短信",
            "search_downloads" to "搜索下载内容",
            "search_coloros_notes" to "搜索便签",
            "search_coloros_recordings" to "搜索录音机",
            "search_recording_summaries" to "搜索录音摘要",
            "search_coloros_memories" to "搜索小布记忆",
            "search_saved_places" to "搜索收藏地点",
            "search_personal_orders" to "搜索个人订单",
            "search_qq_chat_images" to "搜索 QQ 聊天图片",
            "search_wechat_chat_images" to "搜索微信聊天图片",
            "set_setting" to "修改系统设置",
            "set_device_state" to "修改设备状态",
            "app_state_control" to "管理应用状态",
        )
    }
}
