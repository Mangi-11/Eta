package fuck.andes.agent.model

import fuck.andes.config.Prefs
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private const val MAX_TRACE_CHARS = 240

    fun loadConfig(): ModelConfig =
        ModelConfig(
            baseUrl = Prefs.getString(Prefs.Keys.AGENT_BASE_URL).trim(),
            apiKey = Prefs.getString(Prefs.Keys.AGENT_API_KEY).trim(),
            model = Prefs.getString(Prefs.Keys.AGENT_MODEL).trim(),
            systemPrompt = Prefs.getString(Prefs.Keys.AGENT_SYSTEM_PROMPT).trim(),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
        )

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        provider: AgentProviderClient = OpenAiChatCompletionsProvider,
        runController: AgentRunController = AgentRunController(),
        onEvent: (AgentEvent) -> Unit = {}
    ): ModelResponse.Text {
        config.validate()
        val messages = buildInitialMessages(config, prompt, images)
        val tools = buildToolsJson(config.terminalTools)
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        var round = 1
        while (true) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round = round, messageCount = messages.length()))
            val providerResponse = provider.complete(
                request = ProviderRequest(
                    config = config,
                    messages = messages,
                    tools = tools
                ),
                runController = runController
            ) { providerEvent ->
                providerEvent.toAgentEvent(round)?.let(onEvent)
            }
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = parseToolCalls(assistantMessage)
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    toolNames = toolCalls.map { it.name }
                )
            )
            if (toolCalls.isNotEmpty()) {
                messages.put(buildAssistantToolCallMessage(assistantMessage, toolCalls))
                toolCalls.forEach { toolCall ->
                    runController.throwIfCancelled()
                    onEvent(
                        AgentEvent.ToolStarted(
                            round = round,
                            name = toolCall.name,
                            argsPreview = toolCall.argumentsJson.compactTrace()
                        )
                    )
                    val toolResult = toolExecutor.execute(toolCall)
                    runController.throwIfCancelled()
                    onEvent(
                        AgentEvent.ToolFinished(
                            round = round,
                            name = toolCall.name,
                            resultSummary = summarizeToolResult(toolResult),
                            imageCount = toolResult.images.size,
                            imageBytes = toolResult.images.sumOf { it.bytes }
                        )
                    )
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", toolCall.id)
                            .put("content", toolResult.content)
                    )
                    if (toolResult.images.isNotEmpty()) {
                        val imageBytes = toolResult.images.sumOf { it.bytes }
                        messages.put(
                            buildUserMessage(
                                text = "Observation image(s) returned by tool ${toolCall.name}.",
                                images = toolResult.images
                            )
                        )
                        onEvent(
                            AgentEvent.ToolImagesAttached(
                                round = round,
                                toolName = toolCall.name,
                                imageCount = toolResult.images.size,
                                imageBytes = imageBytes
                            )
                        )
                    }
                }
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isNotBlank() && content != "null") {
                onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
                return ModelResponse.Text(content)
            }
            val finishReason = assistantMessage.optString("finish_reason")
            error("模型接口第 $round 轮返回为空${finishReason.ifBlank { "" }}")
        }
    }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(apiKey.isNotBlank()) { "请先配置 API Key" }
        require(model.isNotBlank()) { "请先配置模型名" }
    }

    private fun buildInitialMessages(
        config: ModelConfig,
        prompt: String,
        images: List<ModelImage>
    ): JSONArray {
        val messages = JSONArray()
        if (config.systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", config.systemPrompt)
            )
        }
        if (config.terminalTools) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "当用户明确要求在手机上执行命令、查看 Linux/Android 系统信息、读取/写入文件、查询包名或使用 shell 时，必须调用 terminal 或 run_command/read_file/write_file/list_directory 工具。用户说“执行命令 xxx”时，首轮必须调用 terminal，action=open_and_exec，identity=root，command=xxx；不要调用 search_apps 查询“终端”或“Termux”。不要回答“没有终端应用”或建议用户安装 Termux；这些工具已经在当前 Android 设备上通过内置 Root Shell 可用。"
                    )
            )
        }
        messages.put(buildUserMessage(prompt, images))
        return messages
    }

    private fun buildUserMessage(text: String, images: List<ModelImage>): JSONObject {
        if (images.isEmpty()) {
            return JSONObject()
                .put("role", "user")
                .put("content", text)
        }
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", text)
            )
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", image.dataUrl)
                    )
            )
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    private fun buildToolsJson(terminalTools: Boolean): JSONArray =
        JSONArray()
            .put(
                functionTool(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                functionTool(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                        )
                )
            )
            .put(
                functionTool(
                    name = "open_uri",
                    description = "用 Android ACTION_VIEW 打开一个确定有效的 URI，例如 https、tel、geo 或应用 deep link。不要编造 URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                functionTool(
                    name = "observe_screen",
                    description = "观察当前手机屏幕，返回前台应用、屏幕尺寸、可见 UI 节点。需要视觉判断时设置 include_screenshot=true。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否附加当前屏幕截图给模型，默认 true")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60")
                                )
                        )
                )
            )
            .put(
                functionTool(
                    name = "tap",
                    description = "点击坐标。默认使用最近一次 observe_screen 截图里的像素坐标；如果坐标来自 ui_nodes 的 center，请设置 coordinate_space=screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", coordinateSpaceSchema())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                functionTool(
                    name = "tap_area",
                    description = "点击矩形区域中心。默认使用最近一次 observe_screen 截图里的像素坐标；大按钮、大列表项和可见文字区域优先用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", coordinateSpaceSchema())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                functionTool(
                    name = "tap_element",
                    description = "点击最近一次 observe_screen 返回的 UI 节点 index。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("index", JSONObject().put("type", "integer"))
                        )
                        .put("required", JSONArray().put("index"))
                )
            )
            .put(
                functionTool(
                    name = "long_press",
                    description = "长按坐标。默认使用最近一次 observe_screen 截图里的像素坐标；如果坐标来自 ui_nodes 的 center，请设置 coordinate_space=screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                                .put("coordinate_space", coordinateSpaceSchema())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                functionTool(
                    name = "swipe",
                    description = "从一个坐标滑动到另一个坐标。默认使用最近一次 observe_screen 截图里的像素坐标。向上滑动会让列表向下滚动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "滑动时长，100 到 2000，默认 500")
                                )
                                .put("coordinate_space", coordinateSpaceSchema())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                functionTool(
                    name = "scroll",
                    description = "按方向滚动当前屏幕内容。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                functionTool(
                    name = "input_text",
                    description = "向当前输入框输入文本。输入前通常需要先点击输入框。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要输入的文本，最多 200 字符")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                functionTool(
                    name = "press_key",
                    description = "按系统按键，仅允许 BACK、HOME、ENTER、RECENTS。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("BACK").put("HOME").put("ENTER").put("RECENTS"))
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .also { tools ->
                if (terminalTools) appendTerminalTools(tools)
            }

    private fun appendTerminalTools(tools: JSONArray) {
        tools
            .put(
                functionTool(
                    name = "terminal",
                    description = "Manage Android terminal sessions. For one-shot commands, prefer open_and_exec, e.g. {\"action\":\"open_and_exec\",\"identity\":\"root\",\"command\":\"uname -a\"}. identity can be user or root. Use this when the user asks to execute Android/Linux shell commands, inspect files, query packages, or operate the device through command line. This tool returns stdout, stderr and exit_code.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("open_and_exec"))
                                        .put("description", "Terminal action. Use open_and_exec for one-shot commands.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "Use root when the task needs full Android/Linux device access. Default root.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Android shell command to execute.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Default /data/local/tmp/fuck_andes. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                        )
                        .put("required", JSONArray().put("action").put("command"))
                )
            )
            .put(
                functionTool(
                    name = "run_command",
                    description = "在 Android 设备上用非交互 Root Shell 执行命令。适合系统信息、包管理、文件检查、Linux 命令流水线。每次调用都是新 shell；不要运行交互式或长期驻留命令。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，可使用管道和重定向。")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录，默认 /data/local/tmp/fuck_andes。相对路径也按该目录解析；用户存储可用 ~/ 表示 /storage/emulated/0。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 180，默认 30。")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                functionTool(
                    name = "read_file",
                    description = "读取 Android 文件内容。适合读取配置、日志、小文本文件；大文件用 offset_bytes/max_bytes 分段读取。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "从第几个字节开始，默认 0。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多读取字节数，1 到 262144，默认 65536。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                functionTool(
                    name = "write_file",
                    description = "写入 Android 文件。可覆盖或追加；会自动创建父目录。用于明确需要修改文件的任务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 追加，false 覆盖，默认 false。")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                functionTool(
                    name = "list_directory",
                    description = "列出 Android 目录内容。默认 /data/local/tmp/fuck_andes，输出类似 ls -l。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 200 行，默认 80。")
                                )
                        )
                )
            )
    }

    private fun coordinateSpaceSchema(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("screenshot").put("screen"))
            .put("description", "screenshot 表示最近一次 observe_screen 附图的像素坐标；screen 表示真实设备屏幕坐标。默认 screenshot。")

    private fun functionTool(
        name: String,
        description: String,
        parameters: JSONObject
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters)
            )

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.TextDelta -> AgentEvent.AssistantTextDelta(round, delta.length)
            is ProviderEvent.ToolCallDelta -> AgentEvent.ProviderToolCallDelta(
                round = round,
                index = index,
                name = name,
                argumentsChars = argumentsDelta.length
            )
            is ProviderEvent.Completed -> null
        }

    private fun parseToolCalls(message: JSONObject): List<ToolCall> {
        val toolCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        return buildList {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name").trim()
                if (name.isBlank()) continue
                val arguments = function.opt("arguments")
                add(
                    ToolCall(
                        id = toolCall.optString("id").ifBlank { "tool_call_$index" },
                        name = name,
                        argumentsJson = when (arguments) {
                            null -> "{}"
                            is JSONObject -> arguments.toString()
                            is String -> arguments.ifBlank { "{}" }
                            else -> "{}"
                        }
                    )
                )
            }
        }
    }

    private fun buildAssistantToolCallMessage(
        source: JSONObject,
        toolCalls: List<ToolCall>
    ): JSONObject {
        val rawToolCalls = source.optJSONArray("tool_calls") ?: JSONArray()
        return JSONObject()
            .put("role", "assistant")
            .put("content", source.opt("content") ?: JSONObject.NULL)
            .put("tool_calls", rawToolCalls)
            .also {
                require(toolCalls.isNotEmpty()) { "toolCalls must not be empty" }
            }
    }

    private fun String.compactTrace(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_TRACE_CHARS) it.take(MAX_TRACE_CHARS) + "..." else it }

    private fun summarizeToolResult(result: ToolResult): String =
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
            "chars=${result.content.length}, raw=${result.content.compactTrace()}"
        }

    data class ModelConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val systemPrompt: String,
        val terminalTools: Boolean
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList()
    )

    data class ModelImage(
        val dataUrl: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown"
    )

    sealed interface ModelResponse {
        data class Text(val content: String) : ModelResponse
    }
}
