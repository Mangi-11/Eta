package io.github.mangi.eta.agent.display

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** 单个 Runtime 所有的虚拟屏；查看页只观察同一会话，不抢占主屏。 */
internal object VirtualScreenSession {
    private val lock = Any()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(
            r,
            "eta-display-deadline"
        ).apply { isDaemon = true }
    }
    private val session = AtomicReference<Session?>()
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
        val closed = AtomicBoolean()
        fun close() {
            if (!closed.compareAndSet(false, true)) return
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
                current.input.write(args.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
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
