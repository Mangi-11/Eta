package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Root 子进程持有独立 Surface；只接受固定操作。管道结束即释放 display。 */
internal object RootDisplayCommandMain {
    private const val MARKER = "ETA_DISPLAY_RESULT:"
    @JvmStatic
    fun main(args: Array<String>) {
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        var frames: HandlerThread? = null
        var wakeLock: PowerManager.WakeLock? = null
        try {
            require(Process.myUid() == 0) { "ROOT_REQUIRED" }
            // 即使 Eta 被 ROM 冻结，Root 子进程也不能无限保留 display；Binder 死亡会释放其资源。
            Thread {
                Thread.sleep(15 * 60_000L)
                Process.killProcess(Process.myPid())
            }.apply { name = "eta-root-display-lifetime"; isDaemon = true; start() }
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
            val context =
                activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val input = System.`in`.bufferedReader()
            val setup = JSONObject(input.readLine() ?: return)
            require(setup.optString("action") == "create")
            val width = integer(setup, "width", 320, 1080)
            val height = integer(setup, "height", 480, 1920)
            val dpi = integer(setup, "density", 160, 480)
            val allowOff = setup.optBoolean("allowScreenOff", false)
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            frames = HandlerThread("eta-virtual-frames").apply { start() }
            val handler = Handler(frames.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            val frameStore = FrameStore()
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    if (SystemClock.elapsedRealtime() - frameStore.lastEncoded < 250) return@use
                    val plane = image.planes.firstOrNull() ?: return@use
                    if (plane.pixelStride != 4) return@use
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    val cropped: Bitmap
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer); cropped =
                            Bitmap.createBitmap(padded, 0, 0, width, height)
                    } catch (_: Exception) {
                        padded.recycle(); return@use
                    }
                    val encoded = ByteArrayOutputStream()
                    try {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 75, encoded)
                        frameStore.jpeg = encoded.toByteArray().takeIf { it.size <= 2_000_000 }
                        frameStore.lastEncoded = SystemClock.elapsedRealtime()
                    } finally {
                        if (cropped !== padded) cropped.recycle(); padded.recycle()
                    }
                }
            }, handler)
            // Android 14+ own-focus 和不抢顶层焦点；不使用 AUTO_MIRROR，也不捕获安全 Surface。
            var flags =
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH") or flag("VIRTUAL_DISPLAY_FLAG_TRUSTED") or
                        flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") or flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") or
                        flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED") or flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
            if (allowOff) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
            display = displayManager.createVirtualDisplay(
                "Eta virtual task",
                width,
                height,
                dpi,
                reader.surface,
                flags
            )
                ?: error("DISPLAY_CREATE_FAILED")
            val id = display.display.displayId
            require(id > 0) { "INVALID_DISPLAY" }
            if (allowOff) wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:virtual-task")
                    .apply { acquire(15 * 60_000L) }
            reply(
                JSONObject().put("ok", true).put("displayId", id).put("width", width)
                    .put("height", height).put("independentFocus", true)
            )
            while (true) {
                val line = input.readLine() ?: break
                if (line.length > 128_000) {
                    reply(failure("INVALID_ARGUMENT")); continue
                }
                val request = runCatching { JSONObject(line) }.getOrNull()
                if (request == null) {
                    reply(failure("INVALID_ARGUMENT")); continue
                }
                if (request.optString("action") == "close") {
                    reply(JSONObject().put("ok", true)); break
                }
                try {
                    require(displayManager.getDisplay(id) != null) { "DISPLAY_GONE" }
                    require(allowOff || (power.isInteractive && !keyguard.isDeviceLocked)) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                    val result = when (request.getString("action")) {
                        "observe" -> frameStore.jpeg?.let { jpeg ->
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("width", width).put("height", height)
                                .put("mimeType", "image/jpeg")
                                .put(
                                    "frameAgeMs",
                                    SystemClock.elapsedRealtime() - frameStore.lastEncoded
                                )
                                .put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                        } ?: failure("DISPLAY_FRAME_PENDING")

                        "launch" -> {
                            val component = request.getString("component")
                            require(
                                Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+").matches(
                                    component
                                )
                            ) { "INVALID_COMPONENT" }
                            val packageName = component.substringBefore('/')
                            val target = ComponentName.unflattenFromString(component)
                                ?: error("INVALID_COMPONENT")
                            val info = context.packageManager.getActivityInfo(target, 0)
                            val resizeMode =
                                ActivityInfo::class.java.getField("resizeMode").getInt(info)
                            val resizable = ActivityInfo::class.java.getDeclaredMethod(
                                "isResizeableMode",
                                Int::class.javaPrimitiveType
                            ).invoke(null, resizeMode) as Boolean
                            require(info.enabled && info.exported && resizable && info.launchMode <= ActivityInfo.LAUNCH_SINGLE_TOP) { "DISPLAY_APP_UNSUPPORTED" }
                            // 任何已有应用任务都拒绝迁移；多个 display 不等于应用数据隔离。
                            require(runningTasks().none { it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName }) { "APP_ALREADY_RUNNING" }
                            val output = command(
                                listOf(
                                    "/system/bin/am",
                                    "start",
                                    "--display",
                                    id.toString(),
                                    "-f",
                                    "0x18000000",
                                    "-n",
                                    component
                                )
                            )
                            require(!output.contains("Error:") && !output.contains("Warning: Activity not started")) { "DISPLAY_LAUNCH_REJECTED" }
                            val launched =
                                runningTasks().filter { it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName }
                            require(launched.isNotEmpty() && launched.all {
                                it.javaClass.getField("displayId").getInt(it) == id
                            }) { "DISPLAY_LAUNCH_MISMATCH" }
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("component", component)
                        }

                        "tap" -> {
                            input(
                                id,
                                "tap",
                                integer(request, "x", 0, width - 1).toString(),
                                integer(request, "y", 0, height - 1).toString()
                            ); JSONObject().put("ok", true).put("displayId", id)
                        }

                        "swipe" -> {
                            input(
                                id,
                                "swipe",
                                integer(request, "x1", 0, width - 1).toString(),
                                integer(request, "y1", 0, height - 1).toString(),
                                integer(request, "x2", 0, width - 1).toString(),
                                integer(request, "y2", 0, height - 1).toString(),
                                integer(request, "durationMs", 100, 2000).toString()
                            ); JSONObject().put("ok", true).put("displayId", id)
                        }

                        "back" -> {
                            input(id, "keyevent", "4"); JSONObject().put("ok", true)
                                .put("displayId", id)
                        }

                        else -> failure("UNKNOWN_DISPLAY_ACTION")
                    }
                    reply(result)
                } catch (error: Exception) {
                    reply(
                        failure(
                            if (error is IllegalArgumentException || error is IllegalStateException) error.message
                                ?: "DISPLAY_ACTION_FAILED" else "DISPLAY_ACTION_FAILED"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            reply(failure("ROOT_DISPLAY_UNAVAILABLE"))
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            frames?.quitSafely()
            if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
        }
    }

    private class FrameStore {
        @Volatile
        var jpeg: ByteArray? = null;
        @Volatile
        var lastEncoded = 0L
    }

    private fun flag(name: String) = DisplayManager::class.java.getField(name).getInt(null)

    // Called only by the UID 0 app_process helper, never in Eta's application process.
    // An unavailable task API aborts launch before any activity or input is sent.
    @SuppressLint("BlockedPrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
        val type = Class.forName("android.app.ActivityTaskManager")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
        return type.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType)
            .invoke(manager, 1000) as List<ActivityManager.RunningTaskInfo>
    }

    private fun integer(args: JSONObject, name: String, min: Int, max: Int): Int {
        val number = args.get(name)
        require(
            number is Number && number.toDouble() == number.toInt()
                .toDouble() && number.toInt() in min..max
        ) { "INVALID_ARGUMENT" }
        return number.toInt()
    }

    private fun input(id: Int, vararg args: String) {
        command(listOf("/system/bin/input", "-d", id.toString()) + args)
    }

    private fun command(args: List<String>, limit: Int = 16_000): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val bytes = ByteArrayOutputStream()
        val drain = Thread {
            process.inputStream.use { stream ->
                val buffer = ByteArray(4096); while (true) {
                val n =
                    stream.read(buffer); if (n < 0) break; if (bytes.size() + n <= limit) bytes.write(
                    buffer,
                    0,
                    n
                ) else {
                    process.destroy(); break
                }
            }
            }
        }.apply { start() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); error("DISPLAY_ACTION_TIMEOUT")
        }
        drain.join(1000)
        require(process.exitValue() == 0) { "DISPLAY_ACTION_FAILED" }
        return bytes.toString("UTF-8")
    }

    private fun failure(code: String) = JSONObject().put("ok", false).put("code", code)
        .put("message", "虚拟屏操作未执行；不会回退到主屏")

    private fun reply(value: JSONObject) {
        System.out.println(MARKER + value.toString()); System.out.flush()
    }
}
