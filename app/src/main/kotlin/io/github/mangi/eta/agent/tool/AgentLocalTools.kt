package io.github.mangi.eta.agent.tool

import android.content.Context
import android.view.Display
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.media.ModelImageBudget
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.catalog.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.tool.chat.AgentImageTools
import io.github.mangi.eta.agent.tool.device.ActiveDisplayUnavailableException
import io.github.mangi.eta.agent.tool.device.AgentStructuredDeviceTools
import io.github.mangi.eta.agent.tool.device.AppLaunchTools
import io.github.mangi.eta.agent.tool.device.DeviceContextTool
import io.github.mangi.eta.agent.tool.device.GuiInteractionTools
import io.github.mangi.eta.agent.tool.device.InvalidToolArgumentException
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    skillIndexService: SkillIndexService? = null,
    skillLoader: SkillLoader? = null,
    skillResourceReader: SkillResourceReader? = null,
    githubSkillSource: PublicGitHubSkillSource? = null,
    skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val virtualDisplayEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
    },
    private val isToolEnabled: (String) -> Boolean = {
        Prefs.isToolEnabled(it)
    },
    private val lsposedAvailable: () -> Boolean = { EtaApp.serviceInstance != null },
    private val guiControl: GuiControlCoordinator.RunControl? = null,
    private val imageBudgetProvider: () -> ModelImageBudget = { ModelImageBudget.DEFAULT },
    browserSession: AgentBrowserSession? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)

    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY

    private val browserSessionRef = lazy { browserSession ?: AgentBrowserSession() }
    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
        foregroundAction = ::executeForegroundIntent,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable, imageBudgetProvider)
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    val terminalExecutionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(
            linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath,
            sharedMounts = SharedFolderMounts.current(),
        )
    }
    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        executionContext = terminalExecutionContext,
        backgroundJobs = TerminalRuntime.backgroundJobs(context),
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = terminalExecutionContext.linuxRootfsPath,
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
            rootAvailable = rootAvailable,
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val guiTools = GuiInteractionTools(
        context = context,
        logger = logger,
        deviceController = deviceController,
        screenObservationProvider = screenObservationProvider,
        requireContext = ::requireContext,
        activeDisplayId = { activeDisplayId },
        setActiveDisplayId = { activeDisplayId = it },
        isClosed = closed::get,
        checkCancellation = ::checkCancellation,
    )
    private val appLaunchTools = AppLaunchTools(
        logger = logger,
        requireContext = ::requireContext,
        rootAvailable = rootAvailable,
        virtualDisplayEnabled = virtualDisplayEnabled,
        lsposedAvailable = lsposedAvailable,
        activeDisplayId = { activeDisplayId },
        setActiveDisplayId = { activeDisplayId = it },
        checkCancellation = ::checkCancellation,
    )
    private val skillTools = AgentSkillTools(
        skillIndexService = skillIndexService,
        skillLoader = skillLoader,
        skillResourceReader = skillResourceReader,
        githubSkillSource = githubSkillSource,
        skillPackageInstaller = skillPackageInstaller,
        runAvailableSkillIds = runAvailableSkillIds,
        pendingSkillConflict = pendingSkillConflict,
        isClosed = closed::get,
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        guiTools.clearObservation()
        if (browserSessionRef.isInitialized()) browserSessionRef.value.interruptAgentAction(browserRunId)
        webTools.close()
        terminalController.interruptAll()
        deviceController.close()
        rootCommandExecutor.close()
        skillTools.close()
        guiControl?.close()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val control = guiControl
        return if (control != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            control.withAction { generation -> executeLocal(toolCall, generation) }
        } else {
            executeLocal(toolCall, null)
        }
    }

    private fun executeLocal(
        toolCall: AgentModelClient.ToolCall,
        guiGeneration: Long?,
    ): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            if (!isToolEnabled(toolCall.name)) {
                return@runCatching AgentModelClient.ToolResult(
                    content = errorResult("TOOL_DISABLED", "工具 ${toolCall.name} 已在工具列表中关闭，本次未执行"),
                    sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
                )
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            if (guiGeneration != null && !guiTools.matchesGeneration(guiGeneration)) {
                guiTools.clearObservation()
                if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
                    return@runCatching textResult(
                        errorResult(
                            "NO_OBSERVATION",
                            "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面",
                        ),
                    )
                }
            }
            val preparation = if (AgentToolRequirements.hasForegroundIntentFallback(toolCall.name)) {
                ToolExecutionDecision.Allow
            } else {
                beforeToolExecution(toolCall.name)
            }
            when (val decision = preparation) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) guiTools.clearObservation()
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(appLaunchTools.searchApps(args))
                "launch_app" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.launchApp(args))
                }
                "present_to_user" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.presentToUser(args))
                }
                "open_uri" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.openUri(args))
                }
                "browser_use" -> browserUse(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "observe_screen" -> guiTools.observeScreen(args, guiGeneration)
                "release_gui" -> {
                    guiTools.clearObservation()
                    guiControl?.release()
                    textResult(JSONObject().put("ok", true).put("tool", "release_gui").toString())
                }
                "tap" -> guiTools.afterAction(guiTools.tap(args))
                "tap_area" -> guiTools.afterAction(guiTools.tapArea(args))
                "tap_element" -> guiTools.afterAction(guiTools.tapElement(args))
                "long_press" -> guiTools.afterAction(guiTools.longPress(args))
                "long_press_element" -> guiTools.afterAction(guiTools.longPressElement(args))
                "swipe" -> guiTools.afterAction(guiTools.swipe(args))
                "scroll" -> guiTools.afterAction(guiTools.scroll(args))
                "scroll_element" -> guiTools.afterAction(guiTools.scrollElement(args))
                "type_text" -> guiTools.afterAction(guiTools.typeText(args))
                // 旧会话或旧入口仍可能请求这些工具名；保留执行路径，但不再出现在模型目录里。
                "input_text" -> guiTools.afterAction(guiTools.inputText(args))
                "replace_text" -> guiTools.afterAction(guiTools.replaceText(args))
                "clear_text" -> guiTools.afterAction(guiTools.clearText(args))
                "set_clipboard" -> textResult(guiTools.setClipboard(args))
                "get_clipboard" -> textResult(guiTools.getClipboard())
                "paste_text" -> guiTools.afterAction(guiTools.pasteText(args))
                "press_key" -> guiTools.afterAction(guiTools.pressKey(args))
                "wait" -> textResult(guiTools.waitMs(args))
                "wait_for_text" -> textResult(guiTools.waitForText(args))
                "wait_for_package" -> textResult(guiTools.waitForPackage(args))
                "open_system_panel" -> textResult(guiTools.openSystemPanel(args))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                in AgentTerminalToolCatalog.names -> textResult(
                    terminalTool {
                        if (toolCall.name == "terminal" && args.optString("action").equals("check_root", ignoreCase = true)) {
                            checkRoot()
                        } else {
                            terminalController.executeTool(toolCall.name, args)
                        }
                    },
                )
                in AgentFileToolCatalog.names -> textResult(terminalTool { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillTools.list(args))
                "skills_read" -> textResult(skillTools.read(args))
                "skills_read_resource" -> textResult(skillTools.readResource(args))
                "skills_list_curated" -> textResult(skillTools.listCurated())
                "skills_inspect_github" -> textResult(skillTools.inspectGitHub(args))
                "skills_install_from_github" -> textResult(skillTools.installFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}",
                    ),
                )
            }
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException) throw throwable
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException, is JSONException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        is ActiveDisplayUnavailableException -> "DISPLAY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun executeForegroundIntent(toolName: String, action: () -> JSONObject): JSONObject {
        fun execute(): JSONObject {
            if (closed.get()) throw AgentRunCancelledException()
            if (!isToolEnabled(toolName)) {
                return JSONObject(errorResult("TOOL_DISABLED", "工具 $toolName 已在工具列表中关闭，本次未执行"))
            }
            deviceToolPermissionError(toolName)?.let { return JSONObject(it.content) }
            return when (val decision = beforeToolExecution(toolName)) {
                ToolExecutionDecision.Allow -> {
                    guiTools.clearObservation()
                    action()
                }
                is ToolExecutionDecision.Reject -> JSONObject(errorResult(decision.code, decision.message))
            }
        }
        return guiControl?.withAction { execute() } ?: execute()
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = browserSessionRef.value.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    private fun checkRoot(): String {
        val probe = RootAccess.request(requireContext())
        runBlocking {
            while (probe.isActive && !closed.get()) delay(100)
        }
        if (closed.get()) return errorResult("CANCELLED", "已取消等待 Root 检测")
        val state = RootAccess.state.value
        return JSONObject().put("ok", true).put("tool", "terminal").put("action", "check_root")
            .put("root_available", state.isGranted).put("status", state.status.name.lowercase(Locale.ROOT))
            .put("checking", state.isChecking).put("su_present", state.suPresent)
            .put("uid", if (state.isGranted) 0 else JSONObject.NULL)
            .put(
                "message",
                if (state.isGranted) {
                    "已确认 Root 授权；后续新命令默认使用 Root"
                } else {
                    "尚未确认 Root 授权；请根据 status 检查授权或检测结果，默认 user 不表示终端不支持 Root"
                },
            )
            .toString()
    }

    private fun checkCancellation() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw AgentRunCancelledException()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private companion object {
        val DEVICE_DIRECT_TOOL_NAMES = AgentPhoneToolCatalog.direct + setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = PersonalSearchTools.names + AgentPhoneToolCatalog.reads + setOf(
            "search_notes", "search_system_memories",
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}
