package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
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
import org.json.JSONArray

/** Root 子进程持有独立 Surface；只接受固定操作。管道结束即释放 display。 */
@androidx.annotation.RequiresApi(34)
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
            val width = integer(setup, "width", 320, VirtualScreenProfile.MAX_WIDTH)
            val height = integer(setup, "height", 480, VirtualScreenProfile.MAX_HEIGHT)
            val dpi = integer(setup, "density", 120, VirtualScreenProfile.MAX_DENSITY)
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
                    if (SystemClock.elapsedRealtime() - frameStore.lastEncoded < 33) return@use
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
                        encoded.toByteArray().takeIf { it.size <= 2_000_000 }?.let {
                            frameStore.frame = EncodedFrame(it, SystemClock.elapsedRealtime())
                        }
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
                VirtualDisplayConfig.Builder("Eta virtual task", width, height, dpi)
                    .setSurface(reader.surface).setFlags(flags)
                    .setRequestedRefreshRate(120f).build(),
            )
                ?: error("DISPLAY_CREATE_FAILED")
            val id = display.display.displayId
            require(id > 0) { "INVALID_DISPLAY" }
            if (allowOff) wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:virtual-task")
                    .apply { acquire(15 * 60_000L) }
            reply(
                JSONObject().put("ok", true).put("displayId", id).put("width", width)
                    .put("height", height).put("density", dpi).put("refreshRate", display.display.refreshRate)
                    .put("independentFocus", true)
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
                        "probe" -> JSONObject().put("ok", true).put("displayId", id)
                            .put("refreshRate", display.display.refreshRate)
                            .put("packageName", runningTasks().firstOrNull { taskDisplayId(it) == id }?.topActivity?.packageName.orEmpty())
                        "tasks" -> JSONObject().put("ok", true).put("tasks", JSONArray(
                            runningTasks().filter { taskDisplayId(it) == id &&
                                it.baseActivity?.className != VirtualScreenHomeActivity::class.java.name
                            }.mapNotNull { task -> task.baseActivity?.let { component ->
                                JSONObject().put("taskId", task.taskId).put("packageName", component.packageName)
                            } },
                        ))
                        "switch_task" -> {
                            val taskId = integer(request, "taskId", 1, Int.MAX_VALUE)
                            switchTask(id, taskId)
                            JSONObject().put("ok", true).put("displayId", id)
                        }
                        "observe" -> frameStore.frame?.let { frame ->
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("width", width).put("height", height)
                                .put("frameId", frame.id)
                                .put("mimeType", "image/jpeg")
                                .put(
                                    "frameAgeMs",
                                    SystemClock.elapsedRealtime() - frame.id
                                )
                                .apply {
                                    if (request.optLong("afterFrameId") == frame.id) put("unchanged", true)
                                    else put("image", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP))
                                }
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
                            // Allow re-entry to our own display, but never migrate a user's task.
                            require(runningTasks().none {
                                (it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName) && taskDisplayId(it) != id
                            }) { "APP_ALREADY_RUNNING" }
                            val uri = request.optString("uri")
                            require(uri.length <= 8192 && !uri.contains('\u0000')) { "INVALID_URI" }
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
                                ) + if (uri.isNotBlank()) listOf("-a", "android.intent.action.VIEW", "-d", uri) else emptyList()
                            )
                            require(!output.contains("Error:") && !output.contains("Warning: Activity not started")) { "DISPLAY_LAUNCH_REJECTED" }
                            val launched =
                                runningTasks().filter { it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName }
                            require(launched.isNotEmpty() && launched.all {
                                taskDisplayId(it) == id
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

                        "key" -> {
                            val button = request.getString("button")
                            val code = when (button) {
                                "BACK" -> 4
                                "ENTER" -> 66
                                "HOME", "MENU" -> {
                                    // Secondary displays have no isolated system Recents window.
                                    // Open our own navigator on this display, without routing global keys.
                                    val sessionId = setup.optString("sessionId")
                                    require(Regex("[a-fA-F0-9-]{36}").matches(sessionId)) { "DISPLAY_OWNER_REQUIRED" }
                                    val output = command(listOf("/system/bin/am", "start", "--display", id.toString(),
                                        "-f", "0x10020000", "-n",
                                        "io.github.mangi.eta/${VirtualScreenHomeActivity::class.java.name}",
                                        "--es", "sessionId", sessionId, "--es", "mode", button))
                                    require(!output.contains("Error:")) { "DISPLAY_LAUNCH_REJECTED" }
                                    require(runningTasks().any { it.topActivity?.className == VirtualScreenHomeActivity::class.java.name && taskDisplayId(it) == id }) { "DISPLAY_LAUNCH_MISMATCH" }
                                    null
                                }
                                else -> error("VIRTUAL_ACTION_UNSUPPORTED")
                            }
                            if (code != null) input(id, "keyevent", code.toString())
                            JSONObject().put("ok", true).put("displayId", id)
                        }

                        "long_press" -> {
                            val x = integer(request, "x", 0, width - 1).toString()
                            val y = integer(request, "y", 0, height - 1).toString()
                            input(id, "swipe", x, y, x, y, integer(request, "durationMs", 300, 3000).toString())
                            JSONObject().put("ok", true).put("displayId", id)
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

    private data class EncodedFrame(val jpeg: ByteArray, val id: Long)

    private class FrameStore {
        @Volatile var frame: EncodedFrame? = null
        val lastEncoded: Long get() = frame?.id ?: 0
    }

    private fun flag(name: String) = DisplayManager::class.java.getField(name).getInt(null)

    private fun taskDisplayId(task: ActivityManager.RunningTaskInfo): Int =
        android.app.TaskInfo::class.java.getField("displayId").getInt(task)

    // This runs only in the UID 0 helper; application-process hidden API limits do not apply.
    @SuppressLint("BlockedPrivateApi")
    private fun switchTask(displayId: Int, taskId: Int) {
        require(runningTasks().any { it.taskId == taskId && taskDisplayId(it) == displayId }) { "VIRTUAL_TASK_UNAVAILABLE" }
        val service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityTaskManager").getMethod(
            "startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java,
        )
        val result = method.invoke(service, taskId, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) as Int
        require(result >= 0 && runningTasks().any { it.taskId == taskId && taskDisplayId(it) == displayId }) { "VIRTUAL_TASK_UNAVAILABLE" }
    }

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
