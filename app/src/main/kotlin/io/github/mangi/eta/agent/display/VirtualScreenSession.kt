package io.github.mangi.eta.agent.display

import android.content.Context
import android.util.Base64
import android.os.SystemClock
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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
    private val gestureIds = AtomicLong()
    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-cleanup").apply {
            isDaemon = true
        }
    }

    private class Session(
        val owner: String,
        val process: java.lang.Process,
        val input: BufferedWriter,
        val output: BufferedReader,
        val width: Int,
        val height: Int,
        val allowOff: Boolean
    ) {
        val id = UUID.randomUUID().toString()
        var displayId: Int = -1
        val closed = AtomicBoolean()
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            viewerState.update { if (it.display?.sessionId == id) VirtualScreenViewerState() else it }
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

    fun execute(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean = { false }
    ): AgentModelClient.ToolResult = synchronized(lock) {
        try {
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
            val settings = runBlocking { SettingsDataStore.settings() }
            require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
            require(RootAccess.isGranted) { "ROOT_REQUIRED" }
            val action = args.getString("action")
            if (action == "create") {
                require(owner.isNotBlank()) { "DISPLAY_OWNER_REQUIRED" }
                require(session.get() == null) { "DISPLAY_ALREADY_ACTIVE" }
                val allowOff = args.optBoolean("allowScreenOff", false)
                require(!allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                val width = bounded(args, "width", 320, 1080, 720)
                val height = bounded(args, "height", 480, 1920, 1280)
                val density = bounded(args, "density", 160, 480, 280)
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
                val current = Session(owner, process, input, output, width, height, allowOff)
                check(session.compareAndSet(null, current))
                if (isCancelled()) {
                    current.close(); error("DISPLAY_CANCELLED")
                }
                val deadline = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
                val response = try {
                    input.write(
                        JSONObject().put("action", "create").put("width", width)
                            .put("height", height).put("density", density)
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
                    display = VirtualDisplayInfo(current.id, owner, current.displayId, width, height),
                    lastAction = "create",
                )
                deadlines.schedule(
                    { if (session.compareAndSet(current, null)) current.close() },
                    15,
                    TimeUnit.MINUTES
                )
                return@synchronized AgentModelClient.ToolResult(response.toString())
            }
            val current = session.get() ?: error("NO_VIRTUAL_SCREEN")
            require(owner == current.owner) { "DISPLAY_OWNER_MISMATCH" }
            require(!current.closed.get() && current.process.isAlive) { "DISPLAY_GONE" }
            require(!current.allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
            val timeout = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
            val result = try {
                publishGesture(current, args)
                current.input.write(args.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
            }
            if (action !in setOf("observe", "probe")) {
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
                    current.width,
                    current.height,
                    "virtual_display",
                    true
                )
            ) else emptyList()
            AgentModelClient.ToolResult(result.toString(), images, sensitive = true)
        } catch (error: Exception) {
            session.get()?.let { current ->
                if (current.closed.get() || !current.process.isAlive) {
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

    fun observeForViewer(context: Context): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
            ?: return@synchronized AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"NO_VIRTUAL_SCREEN\"}")
        execute(context, current.owner, JSONObject().put("action", "observe"))
    }

    fun inputForViewer(context: Context, sessionId: String, args: JSONObject): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
        if (current == null || current.id != sessionId || args.optString("action") !in setOf("tap", "swipe", "long_press", "back", "key")) {
            return@synchronized failure("STALE_VIRTUAL_SCREEN")
        }
        viewerState.update { state -> state.copy(display = state.display?.let {
            it.copy(manualInputGeneration = it.manualInputGeneration + 1)
        }) }
        execute(context, current.owner, args)
    }

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
        block(info)
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
