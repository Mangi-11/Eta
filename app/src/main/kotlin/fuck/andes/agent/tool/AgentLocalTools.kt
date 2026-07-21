package fuck.andes.agent.tool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import fuck.andes.agent.browser.AgentBrowserSession
import fuck.andes.agent.browser.BrowserUrlPolicy
import fuck.andes.agent.device.RootShellDeviceController
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.overlay.AgentHapticFeedback
import fuck.andes.agent.overlay.GestureIndicator
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.skill.SkillCompatibilityChecker
import fuck.andes.agent.skill.SkillIndexService
import fuck.andes.agent.skill.SkillLoader
import fuck.andes.agent.terminal.RootShellTerminalController
import fuck.andes.config.Prefs
import fuck.andes.core.AgentLogger
import fuck.andes.core.HookSupport
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
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
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val beforeToolExecution: (String) -> Boolean = { true },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages)
    private val terminalController = RootShellTerminalController(logger)
    private val publishedObservation = AtomicReference(PublishedObservation())
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publishedObservation.set(PublishedObservation())
        AgentBrowserSession.interruptAgentAction(browserRunId)
        terminalController.interruptAll()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        runCatching {
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            ToolArgumentContract.validate(toolCall.name, args)?.let { issue ->
                return@runCatching textResult(
                    errorResult(
                        code = "INVALID_ARGUMENT",
                        message = issue.message,
                    ),
                )
            }
            if (!beforeToolExecution(toolCall.name)) {
                return@runCatching textResult(
                    errorResult(
                        code = "ENTRY_SURFACE_NOT_READY",
                        message = "入口窗口尚未确认关闭；本次工具未执行，请稍后重试",
                    ),
                )
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> textResult(launchApp(args))
                "open_uri" -> textResult(openUri(args))
                "browser_use" -> browserUse(args, toolCall.id)
                "observe_screen" -> observeScreen(args)
                "tap" -> textResult(tap(args))
                "tap_area" -> textResult(tapArea(args))
                "tap_element" -> textResult(tapElement(args))
                "long_press" -> textResult(longPress(args))
                "long_press_element" -> textResult(longPressElement(args))
                "swipe" -> textResult(swipe(args))
                "scroll" -> textResult(deviceController.scroll(args.optString("direction")))
                "scroll_element" -> textResult(scrollElement(args))
                "input_text" -> textResult(inputText(args))
                "replace_text" -> textResult(replaceText(args))
                "clear_text" -> textResult(clearText(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> textResult(pasteText(args))
                "press_key" -> textResult(deviceController.pressKey(args.optString("button")))
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "open_system_panel" -> textResult(deviceController.openSystemPanel(args.optString("panel")))
                "terminal" -> textResult(terminalTool { terminal(args) })
                "run_command" -> textResult(terminalTool { runCommand(args) })
                "read_file" -> textResult(terminalTool { readFile(args) })
                "write_file" -> textResult(terminalTool { writeFile(args) })
                "list_directory" -> textResult(terminalTool { listDirectory(args) })
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            textResult(
                errorResult(
                    code = if (throwable is InvalidToolArgumentException) {
                        "INVALID_ARGUMENT"
                    } else {
                        "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = AgentBrowserSession.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
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
                )
            },
        )
    }

    private fun observeScreen(args: JSONObject): AgentModelClient.ToolResult {
        val startedAt = SystemClock.elapsedRealtime()
        val observation = deviceController.observe(
            includeScreenshot = args.optBoolean("include_screenshot", true),
            includeUiTree = args.optBoolean("include_ui_tree", true),
            maxNodes = args.optInt("max_nodes", 60)
        )
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    private fun tap(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapArea(args: JSONObject): String {
        val x1 = args.optInt("x1")
        val y1 = args.optInt("y1")
        val x2 = args.optInt("x2")
        val y2 = args.optInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace)
        val second = convertPoint(x2, y2, coordinateSpace)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.tapElement(observation, index)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(node.centerX, node.centerY)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.longPressElement(observation, index, durationMs)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(node.centerX, node.centerY, durationMs)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 800)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
        showLongPress(point.x, point.y, durationMs)
        return deviceController.longPress(point.x, point.y, durationMs)
    }

    private fun swipe(args: JSONObject): String {
        val start = convertPoint(
            x = args.optInt("x1"),
            y = args.optInt("y1"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val end = convertPoint(
            x = args.optInt("x2"),
            y = args.optInt("y2"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 500)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
        showSwipe(start.x, start.y, end.x, end.y, durationMs)
        return deviceController.swipe(
            start.x,
            start.y,
            end.x,
            end.y,
            durationMs
        )
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction")
        )
    }

    private fun inputText(args: JSONObject): String {
        val text = args.optString("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text)
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.optString("text"),
            index = index,
            observation = observation,
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation)
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.optString("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.optString("text"))

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.optString("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains")
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000)
        )

    private fun convertPoint(x: Int, y: Int, coordinateSpace: String): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace == "screen" || (requestedSpace.isBlank() && space == null)) {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions()
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "当前没有可用的截图坐标系；请先 observe_screen，或明确设置 coordinate_space=screen",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        logger.info("Agent local tool action=launch_app outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        context.startActivity(intent)
        logger.info("Agent local tool action=open_uri outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", BrowserUrlPolicy.originForModel(uriText))
                }
            }
            .toString()
    }

    private fun runCommand(args: JSONObject): String =
        terminalController.runCommand(
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutSeconds = args.optInt("timeout_seconds", 30)
        )

    private fun terminal(args: JSONObject): String {
        return terminalController.terminalAction(
            action = args.optString("action", "open_and_exec"),
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutMs = args.optInt("timeout_ms", 30_000),
            identity = args.optString("identity", "root"),
            mergeStderr = args.optBoolean("merge_stderr", false),
            sessionId = args.optString("session_id").ifBlank { null },
            jobId = args.optString("job_id").ifBlank { null },
            async = args.optBoolean("async", false),
            offsetChars = args.optInt("offset_chars", 0),
            maxChars = args.optInt("max_chars", 8_000),
            closeIfDone = args.optBoolean("close_if_done", false)
        )
    }

    private fun readFile(args: JSONObject): String =
        terminalController.readFile(
            path = args.optString("path"),
            offsetBytes = args.optInt("offset_bytes", 0),
            maxBytes = args.optInt("max_bytes", 65_536)
        )

    private fun writeFile(args: JSONObject): String =
        terminalController.writeFile(
            path = args.optString("path"),
            content = args.optString("content"),
            append = args.optBoolean("append", false)
        )

    private fun listDirectory(args: JSONObject): String =
        terminalController.listDirectory(
            path = args.optString("path", "/data/local/tmp/fuck_andes"),
            showHidden = args.optBoolean("show_hidden", false),
            limit = args.optInt("limit", 80)
        )

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
    )

    private fun showTap(x: Int, y: Int) {
        GestureIndicator.showTap(context, x, y)
    }

    private fun showLongPress(x: Int, y: Int, durationMs: Int) {
        GestureIndicator.showLongPress(context, x, y, durationMs)
    }

    private fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )
}
