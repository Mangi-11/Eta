package io.github.mangi.eta.agent.display

import android.content.Context
import android.util.Base64
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

/** Runtime owns the display; viewer inputs are serialized with its tools. */
internal object VirtualScreenSession {
    private val lock = Any()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(
            r,
            "eta-display-deadline"
        ).apply { isDaemon = true }
    }
    private val session = AtomicReference<Session?>()
    private val viewerState = MutableStateFlow(VirtualScreenViewerState())
    val state = viewerState.asStateFlow()
    private val viewerVisibility = VirtualScreenViewerVisibility()
    private val previewUpdates = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-preview").apply { isDaemon = true }
    }
    private val gestureIds = AtomicLong()
    private val operationIds = AtomicLong()
    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-cleanup").apply {
            isDaemon = true
        }
    }

    private class Session(
        val context: Context,
        val owner: String,
        val process: java.lang.Process,
        val input: BufferedWriter,
        val output: BufferedReader,
        val width: Int,
        val height: Int,
        val allowOff: Boolean,
        runId: String?,
    ) {
        val id = UUID.randomUUID().toString()
        var displayId: Int = -1
        val closed = AtomicBoolean()
        val runLease = VirtualScreenRunLease(runId)
        var progressGuard = VirtualScreenProgressGuard()
        var progressDecision: VirtualScreenProgressGuard.Decision? = null
        var stop: AgentModelClient.ToolStop? = null
        var pausedPackageName: String = ""
        var pausedManualGeneration: Long = -1L
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            session.compareAndSet(this, null)
            viewerState.update {
                if (it.display?.sessionId != id) it else it.copy(
                    display = null, gesture = null, activeRunId = null,
                    taskPhase = if (it.taskPhase == VirtualScreenTaskPhase.RUNNING) VirtualScreenTaskPhase.STOPPED else it.taskPhase,
                )
            }
            VirtualScreenNotification.cancel(context, id)
            AgentExecutionService.release("virtual-screen-$id")
            // 不等待 execute 持有的锁或正在读取的帧；主线程取消必须立即返回。
            process.destroy()
            cleanup.execute {
                runCatching { process.outputStream.close() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }

    fun isActive(): Boolean = session.get()?.let { !it.closed.get() && it.process.isAlive } == true

    fun isOwnedBy(owner: String): Boolean = session.get()?.let {
        it.owner == owner && !it.closed.get() && it.process.isAlive
    } == true

    /** Idle displays may be replaced by another conversation; an executing run keeps exclusive ownership. */
    fun prepareForRun(owner: String, runId: String): Boolean = synchronized(lock) {
        val current = session.get() ?: return@synchronized true
        if (current.closed.get() || !current.process.isAlive) {
            current.close()
            return@synchronized true
        }
        if (current.owner == owner) {
            val acquired = current.runLease.acquire(runId)
            if (acquired && viewerState.value.activeRunId != runId) {
                current.progressGuard = VirtualScreenProgressGuard()
                current.progressDecision = null
                current.stop = null
                current.pausedPackageName = ""
                current.pausedManualGeneration = -1L
                viewerState.update { if (it.display?.sessionId == current.id) it.beginRun(runId) else it }
                updateIdleTimeout()
            }
            return@synchronized acquired
        }
        if (!current.runLease.retireIdle()) return@synchronized false
        current.close()
        true
    }

    fun releaseRun(owner: String, runId: String, retain: Boolean, cancelled: Boolean = false,
        paused: Boolean = false) = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized
        if (!current.runLease.release(runId, retain)) return@synchronized
        viewerState.update { if (it.display?.sessionId == current.id) it.finishRun(runId, retain, cancelled, paused) else it }
        if (!retain) current.close() else updateIdleTimeout()
    }

    fun updateIdleTimeout() = synchronized(lock) {
        val current = session.get() ?: return@synchronized
        val minutes = runBlocking { SettingsDataStore.settings() }.virtualScreenIdleTimeoutMinutes
        execute(current.context, current.owner, JSONObject().put("action", "lifecycle")
            .put("idleTimeoutMinutes", minutes).put("activeRun", viewerState.value.activeRunId != null))
        Unit
    }

    /** Only atomic bookkeeping happens on the main thread; root IPC stays serialized. */
    fun setViewerVisible(viewerId: String, visible: Boolean) {
        if (visible) viewerVisibility.show(viewerId) else if (!viewerVisibility.hide(viewerId)) return
        previewUpdates.execute {
            synchronized(lock) {
                val current = session.get() ?: return@synchronized
                if (current.closed.get() || !current.process.isAlive) return@synchronized
                execute(current.context, current.owner, JSONObject().put("action", "preview")
                    .put("visible", viewerVisibility.visible), manual = true)
            }
        }
    }

    fun recordOperation(owner: String, runId: String?, name: String, success: Boolean, manual: Boolean = false) {
        val current = session.get()?.takeIf { it.owner == owner && !it.closed.get() } ?: return
        val operation = VirtualScreenOperation(operationIds.incrementAndGet(), name, System.currentTimeMillis(), success, manual)
        viewerState.update {
            if (it.display?.sessionId != current.id || runId != null && it.activeRunId != runId) it else it.record(operation)
        }
    }

    fun execute(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean = { false },
        runId: String? = null,
        appRestartApproved: Boolean = false,
        manual: Boolean = false,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        try {
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
            val settings = runBlocking { SettingsDataStore.settings() }
            require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
            require(RootAccess.isGranted) { "ROOT_REQUIRED" }
            val action = args.getString("action")
            if (action == "launch" && args.optBoolean("restartApp")) {
                require(appRestartApproved || settings.virtualScreenAutoRestartApps) { "APP_RESTART_PERMISSION_REQUIRED" }
            }
            if (action == "restart") require(appRestartApproved) { "APP_RESTART_PERMISSION_REQUIRED" }
            if (action == "create") {
                require(owner.isNotBlank()) { "DISPLAY_OWNER_REQUIRED" }
                require(session.get() == null) { "DISPLAY_ALREADY_ACTIVE" }
                val allowOff = args.optBoolean("allowScreenOff", false)
                require(!allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                val profile = VirtualScreenProfile.from(context)
                val width = bounded(args, "width", 320, VirtualScreenProfile.MAX_WIDTH, profile.width)
                val height = bounded(args, "height", 480, VirtualScreenProfile.MAX_HEIGHT, profile.height)
                val density = bounded(args, "density", 120, VirtualScreenProfile.MAX_DENSITY, profile.density)
                val apk = context.applicationInfo.sourceDir
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val process = ProcessBuilder(
                    "su",
                    "-c",
                    "CLASSPATH=${quote(apk)} app_process /system/bin io.github.mangi.eta.agent.display.RootDisplayCommandMain"
                ).start()
                Thread {
                    runCatching {
                        process.errorStream.use { stream ->
                            val buffer =
                                ByteArray(4096); while (stream.read(buffer) >= 0) { /* 持续排空，不记录画面或私有数据。 */
                        }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                val current = Session(context.applicationContext, owner, process, input, output, width, height, allowOff, runId)
                check(session.compareAndSet(null, current))
                Thread {
                    runCatching { process.waitFor() }
                    current.close()
                }.apply { name = "eta-display-exit"; isDaemon = true; start() }
                if (!AgentExecutionService.acquire(context, "virtual-screen-${current.id}", onStop = current::close)) {
                    current.close(); error("DISPLAY_LIFECYCLE_UNAVAILABLE")
                }
                if (isCancelled() || current.closed.get()) {
                    AgentExecutionService.release("virtual-screen-${current.id}")
                    current.close(); error("DISPLAY_CANCELLED")
                }
                val deadline = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
                val response = try {
                    input.write(
                        JSONObject().put("action", "create").put("width", width)
                            .put("height", height).put("density", density)
                            .put("sessionId", current.id)
                            .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                            .put("activeRun", runId != null)
                            .put("allowScreenOff", allowOff).toString()
                    ); input.newLine(); input.flush()
                    readResponse(output)
                } catch (error: Exception) {
                    current.close(); throw error
                } finally {
                    deadline.cancel(false)
                }
                if (!response.optBoolean("ok") || response.optInt("displayId") <= 0 || current.closed.get()) {
                    current.close()
                    error(response.optString("code", "ROOT_DISPLAY_UNAVAILABLE"))
                }
                current.displayId = response.getInt("displayId")
                viewerState.value = VirtualScreenViewerState(
                    display = VirtualDisplayInfo(current.id, owner, current.displayId, width, height, density = density),
                    lastAction = "create",
                    taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                    activeRunId = runId,
                )
                VirtualScreenNotification.show(context, current.id)
                if (current.closed.get()) {
                    viewerState.update { if (it.display?.sessionId == current.id) VirtualScreenViewerState() else it }
                    VirtualScreenNotification.cancel(context, current.id)
                    error("DISPLAY_CANCELLED")
                }
                return@synchronized AgentModelClient.ToolResult(response.toString())
            }
            val current = session.get() ?: error("NO_VIRTUAL_SCREEN")
            require(owner == current.owner) { "DISPLAY_OWNER_MISMATCH" }
            require(!current.closed.get() && current.process.isAlive) { "DISPLAY_GONE" }
            require(!current.allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
            if (!manual && action in MUTATING_ACTIONS) {
                checkUiAction(context, owner, args)?.let { return@synchronized it }
            }
            val packet = JSONObject(args.toString())
            if (action in setOf("tap", "swipe", "long_press") && !packet.has("expectedWidth")) {
                viewerState.value.display?.let { info ->
                    packet.put("expectedWidth", info.width).put("expectedHeight", info.height).put("expectedRotation", info.rotation)
                }
            }
            val timeout = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
            val result = try {
                publishGesture(current, args)
                current.input.write(packet.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
            }
            if (result.optBoolean("ok") && action in setOf("probe", "observe", "launch", "switch_task")) {
                viewerState.update { state ->
                    val info = state.display?.takeIf { it.sessionId == current.id } ?: return@update state
                    val next = info.withGeometry(result.optInt("width", info.width), result.optInt("height", info.height),
                        result.optInt("rotation", info.rotation)).copy(
                        focusedPackage = when (action) {
                            "probe" -> result.optString("packageName")
                            "observe" -> info.focusedPackage
                            else -> ""
                        },
                    )
                    state.copy(display = next, gesture = state.gesture.takeIf { info.hasSameGeometry(next) })
                }
            }
            if (action !in setOf("observe", "probe", "preview", "lifecycle")) {
                viewerState.update { it.copy(lastAction = action) }
            }
            if (action == "close") {
                current.close(); session.compareAndSet(current, null)
            }
            val encoded = result.optString("image")
            result.remove("image")
            val images = if (encoded.isNotBlank() && result.optBoolean("ok")) listOf(
                AgentModelClient.ModelImage(
                    "data:image/jpeg;base64,$encoded",
                    "image/jpeg",
                    Base64.decode(encoded, Base64.DEFAULT).size,
                    result.optInt("width", current.width),
                    result.optInt("height", current.height),
                    "virtual_display",
                    true
                )
            ) else emptyList()
            val toolResult = AgentModelClient.ToolResult(result.toString(), images, sensitive = true)
            if (!manual && action == "observe") checkUiObservation(context, owner)?.let { return@synchronized it }
            finishUiAction(owner, toolResult, !manual && action in MUTATING_ACTIONS)
        } catch (error: Exception) {
            session.get()?.let { current ->
                if (current.closed.get() || !current.process.isAlive ||
                    current.owner == owner && error.message in setOf("ROOT_REQUIRED", "VIRTUAL_SCREEN_DISABLED", "SCREEN_OFF_PERMISSION_REQUIRED")) {
                    current.close()
                    session.compareAndSet(current, null)
                }
            }
            AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put(
                    "code",
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message
                        ?: "VIRTUAL_SCREEN_FAILED" else "VIRTUAL_SCREEN_FAILED"
                )
                    .put(
                        "message",
                        "虚拟屏操作未执行；不会回退到主屏。请确认 Root、虚拟屏开关和设备接口。"
                    ).toString()
            )
        }
    }

    fun observeForViewer(context: Context, afterFrameId: Long = 0, viewerId: String? = null): AgentModelClient.ToolResult = synchronized(lock) {
        if (viewerId != null && !viewerVisibility.isCurrent(viewerId)) return@synchronized failure("VIEWER_HIDDEN")
        val current = session.get()
            ?: return@synchronized AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"NO_VIRTUAL_SCREEN\"}")
        execute(context, current.owner, JSONObject().put("action", "observe").put("afterFrameId", afterFrameId)
            .put("preview", viewerId != null), manual = true)
    }

    fun inputForViewer(context: Context, sessionId: String, args: JSONObject): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
        if (current == null || current.id != sessionId || args.optString("action") !in setOf("tap", "swipe", "long_press", "back", "key", "switch_task", "text")) {
            return@synchronized failure("STALE_VIRTUAL_SCREEN")
        }
        viewerState.update { state -> state.copy(display = state.display?.let {
            it.copy(manualInputGeneration = it.manualInputGeneration + 1)
        }) }
        // Manual input is outside the model budget, and its generation starts a new evidence scope.
        execute(context, current.owner, args, manual = true).also { result ->
            val name = if (args.optString("action") == "key") args.optString("button").lowercase() else args.optString("action")
            recordOperation(current.owner, null, name, JSONObject(result.content).optBoolean("ok"), manual = true)
        }
    }

    fun tasksForViewer(context: Context, sessionId: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        execute(context, current.owner, JSONObject().put("action", "tasks"))
    }

    /** Called for both Root input and accessibility node actions while the display lock is held. */
    fun checkUiAction(context: Context, owner: String, args: JSONObject): AgentModelClient.ToolResult? = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        current.stop?.let { return@synchronized pausedResult(current, it) }
        val action = progressAction(args) ?: return@synchronized null
        val (evidence, failure) = progressEvidence(context, current)
        failure?.let { return@synchronized it }
        val decision = current.progressGuard.beforeAction(action, requireNotNull(evidence))
        current.progressDecision = decision
        if (decision.paused) pause(current, "UI_NO_PROGRESS",
            "虚拟屏连续操作未检测到界面进展（同一目标已尝试 ${decision.repeatedAttempts} 次，共 ${decision.attempts} 次）。" +
                "本次操作未执行，任务已暂停并保留现场。这不能证明应用卡死，也可能是未命中、加载或画面异常；请检查后继续。")
        else null
    }

    fun checkUiObservation(context: Context, owner: String): AgentModelClient.ToolResult? = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        current.stop?.let { return@synchronized pausedResult(current, it) }
        val (evidence, failure) = progressEvidence(context, current)
        failure?.let { return@synchronized it }
        if (current.progressGuard.onObservation(requireNotNull(evidence)).paused) pause(current, "UI_NO_PROGRESS",
            "虚拟屏操作后多次观察仍未检测到界面进展，已暂停本轮任务并保留现场。请检查应用、定位和加载状态后继续；动作效果尚未确认。")
        else null
    }

    private fun progressEvidence(context: Context, current: Session): Pair<VirtualScreenProgressGuard.Evidence?, AgentModelClient.ToolResult?> {
        val owner = current.owner
        val probeResult = execute(context, owner, JSONObject().put("action", "probe").put("includeInputHealth", true))
        val probe = JSONObject(probeResult.content)
        if (!probe.optBoolean("ok")) return null to probeResult
        if (probe.optJSONObject("input_health")?.optString("status") == "unresponsive") {
            return null to pause(current, "UI_APP_UNRESPONSIVE",
                "系统检测到虚拟屏当前应用窗口无响应，已暂停本轮自动操作并保留现场。动作效果尚未确认，请检查应用后继续；不要重复发布、支付或发送。")
        }
        val info = viewerState.value.display ?: return null to failure("NO_VIRTUAL_SCREEN")
        val packageName = probe.optString("packageName")
        val nodes = runCatching {
            AgentAccessibilityService.current()?.captureNodeSnapshot(120, current.displayId)
                ?.takeIf { it.displayId == current.displayId && it.packageName == packageName && it.nodes.isNotEmpty() }
        }.getOrNull()
        val nodeFingerprint = nodes?.nodes?.let { values ->
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { node ->
                if (node.className.endsWith("ProgressBar")) return@forEach
                val bounds = node.bounds
                val value = JSONArray(listOf(stableText(node.text), stableText(node.desc), node.className, node.viewId,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, node.enabled, node.focused,
                    node.editable, node.clickable, node.scrollable)).toString()
                digest.update(value.toByteArray(Charsets.UTF_8))
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (nodeFingerprint == null) {
            val captured = execute(context, owner, JSONObject().put("action", "probe").put("captureFingerprint", true))
            val fresh = JSONObject(captured.content)
            if (!fresh.optBoolean("ok")) return null to captured
            probe.put("frameFingerprint", fresh.opt("frameFingerprint") ?: JSONObject.NULL)
        }
        val evidence = VirtualScreenProgressGuard.Evidence(
            scope = listOf(current.id, viewerState.value.activeRunId, info.manualInputGeneration,
                packageName, probe.optString("activity")).joinToString(":"),
            nodes = nodeFingerprint,
            image = probe.optString("frameFingerprint").toLongOrNull(),
            window = probe.optJSONObject("input_health")?.optString("window")?.takeIf { it.isNotBlank() && it != "null" },
        )
        return evidence to null
    }

    private fun stableText(text: String): String = text.replace(Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), "<time>")

    fun attachStop(owner: String, result: AgentModelClient.ToolResult): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized result
        current.stop?.let { pausedResult(current, it) } ?: result
    }

    /** Recovery targets the current app only; the helper checks ownership again before force-stop. */
    fun restartPausedApp(context: Context, owner: String, runId: String,
        isCancelled: () -> Boolean): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner && !it.closed.get() }
            ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        if (viewerState.value.activeRunId != runId || current.stop?.isVirtualUiPause != true) {
            return@synchronized failure("UI_RECOVERY_UNAVAILABLE")
        }
        if (current.pausedPackageName.isBlank() ||
            viewerState.value.display?.manualInputGeneration != current.pausedManualGeneration) {
            return@synchronized failure("UI_RECOVERY_TARGET_CHANGED")
        }
        val probe = execute(context, owner, JSONObject().put("action", "probe"), isCancelled)
        val observed = JSONObject(probe.content)
        if (!observed.optBoolean("ok")) return@synchronized probe
        val packageName = observed.optString("packageName")
        if (packageName != current.pausedPackageName) return@synchronized failure("UI_RECOVERY_TARGET_CHANGED")
        val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: return@synchronized failure("APP_NOT_LAUNCHABLE")
        val restarted = execute(context, owner, JSONObject().put("action", "restart")
            .put("component", component.flattenToString()), isCancelled, appRestartApproved = true)
        if (JSONObject(restarted.content).optBoolean("ok")) {
            current.progressGuard = VirtualScreenProgressGuard()
            current.progressDecision = null
            current.stop = null
            current.pausedPackageName = ""
            current.pausedManualGeneration = -1L
            viewerState.update { state -> state.copy(display = state.display?.let { info ->
                if (info.sessionId != current.id) info else info.copy(
                    manualInputGeneration = info.manualInputGeneration + 1, focusedPackage = "",
                )
            }) }
        }
        restarted
    }

    fun finishUiAction(owner: String, result: AgentModelClient.ToolResult, mutation: Boolean = true): AgentModelClient.ToolResult = synchronized(lock) {
        if (!mutation || result.stop != null) return@synchronized result
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized result
        val json = JSONObject(result.content)
        if (json.optString("code") == "UI_INPUT_TIMEOUT") {
            return@synchronized pause(current, "UI_INPUT_TIMEOUT",
                "虚拟屏输入处理超时，动作可能已经执行；已暂停本轮任务并保留现场，请检查应用状态，勿直接重复有副作用的操作。")
        }
        if (json.optString("code") == "INPUT_DISPATCH_UNCONFIRMED") {
            val probe = execute(current.context, owner, JSONObject().put("action", "probe").put("includeInputHealth", true))
            if (JSONObject(probe.content).optJSONObject("input_health")?.optString("status") == "unresponsive") {
                return@synchronized pause(current, "UI_APP_UNRESPONSIVE",
                    "系统检测到虚拟屏当前应用窗口无响应，已暂停本轮任务并保留现场；刚才的输入可能已经执行，请核对后继续。")
            }
        }
        val decision = current.progressDecision ?: return@synchronized result
        json.put("effect", "unconfirmed").put("progress", JSONObject()
            .put("attempts_without_change", decision.attempts).put("same_target_attempts", decision.repeatedAttempts)
            .put("warning", decision.warning).put("note", if (decision.warning)
                "连续操作前未检测到界面变化。输入完成不等于目标已生效；请观察并修正定位或换一种路径，继续无进展将暂停。"
                else "输入请求结果不代表目标已生效；请根据后续观察核对。"))
        result.copy(content = json.toString())
    }

    private fun pause(current: Session, code: String, message: String): AgentModelClient.ToolResult {
        val stop = AgentModelClient.ToolStop(code, message)
        current.stop = stop
        current.pausedPackageName = viewerState.value.display?.focusedPackage.orEmpty()
        current.pausedManualGeneration = viewerState.value.display?.manualInputGeneration ?: -1L
        return pausedResult(current, stop)
    }

    private fun pausedResult(current: Session, stop: AgentModelClient.ToolStop) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", stop.code).put("message", stop.message)
            .put("display_id", current.displayId).put("effect", "unconfirmed").put("paused", true).toString(),
        sensitive = true, stop = stop,
    )

    private fun progressAction(args: JSONObject): VirtualScreenProgressGuard.Action? {
        val action = args.optString("action")
        fun point(key: String) = (args.opt(key) as? Number)?.toInt()
        return when (action) {
            "tap", "long_press" -> if (point("x") != null && point("y") != null)
                VirtualScreenProgressGuard.Action(action, x = point("x"), y = point("y")) else null
            "swipe" -> if (listOf("x1", "y1", "x2", "y2").all { point(it) != null })
                VirtualScreenProgressGuard.Action(action, x = point("x1"), y = point("y1"), endX = point("x2"), endY = point("y2")) else null
            "key", "back" -> VirtualScreenProgressGuard.Action("key", if (action == "back") "BACK" else args.optString("button"))
            "text" -> if (args.has("text")) VirtualScreenProgressGuard.Action("text", args.optBoolean("replace").toString()) else null
            "launch" -> if (args.has("component")) VirtualScreenProgressGuard.Action("launch", args.optString("component")) else null
            "switch_task" -> if (args.has("taskId")) VirtualScreenProgressGuard.Action("switch_task", args.optString("taskId")) else null
            else -> null
        }
    }

    private val MUTATING_ACTIONS = setOf("tap", "long_press", "swipe", "key", "back", "text", "launch", "switch_task")

    /** Probe the Root process while holding the same lock as manual input and other tools. */
    fun withDisplay(
        context: Context,
        owner: String,
        isCancelled: () -> Boolean = { false },
        block: (VirtualDisplayInfo) -> AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        val probe = execute(context, owner, JSONObject().put("action", "probe"), isCancelled)
        if (!JSONObject(probe.content).optBoolean("ok")) return@synchronized probe
        val info = viewerState.value.display ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        block(info.copy(focusedPackage = JSONObject(probe.content).optString("packageName")))
    }

    fun showNodeGesture(info: VirtualDisplayInfo, action: String, x: Int, y: Int, durationMs: Int = 500, endX: Int = x, endY: Int = y) {
        val current = session.get()?.takeIf { it.id == info.sessionId } ?: return
        publishGesture(current, JSONObject().put("action", action).put("x", x).put("y", y)
            .put("x1", x).put("y1", y).put("x2", endX).put("y2", endY).put("durationMs", durationMs))
    }

    private fun publishGesture(current: Session, args: JSONObject) {
        val action = args.optString("action")
        if (action !in setOf("tap", "long_press", "swipe")) return
        val x = args.optInt(if (action == "swipe") "x1" else "x", -1)
        val y = args.optInt(if (action == "swipe") "y1" else "y", -1)
        val endX = if (action == "swipe") args.optInt("x2", -1) else x
        val endY = if (action == "swipe") args.optInt("y2", -1) else y
        if (x !in 0 until current.width || y !in 0 until current.height || endX !in 0 until current.width || endY !in 0 until current.height) return
        viewerState.update { state -> state.copy(gesture = VirtualScreenGesture(
            gestureIds.incrementAndGet(), current.id, action, x, y, endX, endY,
            args.optInt("durationMs", 500), SystemClock.elapsedRealtime(),
        )) }
    }

    private fun failure(code: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).toString(), sensitive = true,
    )

    fun closeOwner(owner: String) {
        session.get()?.takeIf { it.owner == owner }?.let { current ->
            if (session.compareAndSet(current, null)) current.close()
        }
    }

    fun revokePermission() {
        session.getAndSet(null)?.close()
    }

    private fun readResponse(output: BufferedReader): JSONObject {
        repeat(20) {
            val line = output.readLine() ?: error("ROOT_DISPLAY_DISCONNECTED")
            require(line.length <= 2_800_000) { "DISPLAY_OUTPUT_TOO_LARGE" }
            if (line.startsWith("ETA_DISPLAY_RESULT:")) return JSONObject(line.removePrefix("ETA_DISPLAY_RESULT:"))
        }
        error("DISPLAY_OUTPUT_INVALID")
    }

    private fun bounded(args: JSONObject, name: String, min: Int, max: Int, default: Int): Int {
        if (!args.has(name)) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble() == value.toInt()
                .toDouble() && value.toInt() in min..max
        ) { "INVALID_DISPLAY_DIMENSIONS" }
        return value.toInt()
    }
}
