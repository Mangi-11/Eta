package fuck.andes.agent.model

import java.net.URI
import org.json.JSONObject

/** 只生成可展示、可记录且不泄露敏感参数的工具摘要。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            "terminal" -> summarizeTerminalArguments(toolCall.argumentsJson)
            "run_command" -> summarizeTextLength("执行命令", toolCall.argumentsJson, "command")
            "write_file" -> summarizeTextLength("写入文件", toolCall.argumentsJson, "content")
            "read_file" -> "读取文件"
            "list_directory" -> "列出目录"
            "input_text", "replace_text", "paste_text", "set_clipboard" ->
                summarizeTextLength("输入文本", toolCall.argumentsJson, "text")
            "search_apps" -> summarizeTextLength("搜索应用", toolCall.argumentsJson, "query")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            else -> "参数已接收"
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

    private fun summarizeTerminalArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").takeIf { it in TERMINAL_ACTIONS }
            val identity = arguments.optString("identity").takeIf { it == "root" || it == "user" }
            val commandChars = arguments.optString("command").length.takeIf { it > 0 }
            buildList {
                add("终端")
                action?.let(::add)
                identity?.let(::add)
                commandChars?.let { add("command_chars=$it") }
            }.joinToString(" · ")
        }.getOrDefault("终端")

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · chars=$chars"
        }.getOrDefault(label)

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "观察屏幕 · screenshot=${arguments.optBoolean("include_screenshot", true)} · " +
                "ui_tree=${arguments.optBoolean("include_ui_tree", true)}"
        }.getOrDefault("观察屏幕")

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String =
        if (toolName == BROWSER_TOOL_NAME) {
            summarizeBrowserResult(result)
        } else {
            summarizeGenericResult(result)
        }

    private fun summarizeGenericResult(result: AgentModelClient.ToolResult): String =
        runCatching {
            val json = JSONObject(result.content)
            val apps = json.optJSONArray("apps")
            val candidates = json.optJSONArray("candidates")
            buildString {
                append("ok=${json.opt("ok")}")
                val code = json.optString("code")
                if (code.isNotBlank()) append(", code=").append(code)
                if (apps != null) append(", apps=").append(apps.length())
                if (candidates != null) append(", candidates=").append(candidates.length())
                append(", chars=").append(result.content.length)
                if (result.images.isNotEmpty()) append(", images=").append(result.images.size)
            }
        }.getOrElse {
            "chars=${result.content.length}"
        }

    private fun summarizeBrowserResult(result: AgentModelClient.ToolResult): String =
        runCatching {
            val json = JSONObject(result.content)
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

            buildString {
                append("ok=").append(json.optBoolean("ok", false))
                append(", action=").append(action)
                if (host != null) append(", host=").append(host)
                if (title != null) append(", title=").append(title)
                if (textChars != null) append(", text_chars=").append(textChars)
                if (elementCount != null) append(", elements=").append(elementCount)
                append(", truncated=").append(json.optBoolean("truncated", false))
            }
        }.getOrElse {
            "ok=false, action=unknown, truncated=false"
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= 80) it else it.take(80) + "..." }

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

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        val TERMINAL_ACTIONS = setOf(
            "open",
            "open_and_exec",
            "exec",
            "read",
            "close",
            "read_async_result",
            "cancel_async",
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
    }
}
