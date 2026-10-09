package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.view.Display
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
        var capture: VirtualScreenFrameCapture? = null
        var wakeLock: PowerManager.WakeLock? = null
        var recoveryWakeLock: PowerManager.WakeLock? = null
        var rotationListener: VirtualScreenAppRotation? = null
        try {
            require(Process.myUid() == 0) { "ROOT_REQUIRED" }
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
            val idleTimer = VirtualScreenIdleTimer(setup.optInt("idleTimeoutMinutes", 20),
                SystemClock::elapsedRealtime, setup.optBoolean("activeRun"))
            Thread {
                while (true) {
                    Thread.sleep(1000)
                    if (idleTimer.expired()) Process.killProcess(Process.myPid())
                }
            }.apply { name = "eta-root-display-idle"; isDaemon = true; start() }
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            frames = HandlerThread("eta-virtual-frames").apply { start() }
            val handler = Handler(frames.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            val frameStore = VirtualScreenFrameCapture(reader, handler) { display?.display }
            capture = frameStore
            // Android 14+ own-focus 和不抢顶层焦点；不使用 AUTO_MIRROR，也不捕获安全 Surface。
            var flags =
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH") or flag("VIRTUAL_DISPLAY_FLAG_TRUSTED") or
                        flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") or flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT") or
                        flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") or
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
            configureAppRotation(id)
            val appRotation = VirtualScreenAppRotation(display.display, width > height)
            rotationListener = appRotation
            val localIme = configureLocalIme(id)
            val textInput = VirtualScreenRootTextInput(id)
            val inputInjector = VirtualScreenInputInjector(id)
            if (allowOff) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:virtual-task")
                    .apply { acquire() }
                // CPU wakefulness alone does not keep the virtual display group interactive.
                recoveryWakeLock = createRecoveryWakeLock(power, display.display).apply { acquire() }
            }
            reply(
                JSONObject().put("ok", true).put("displayId", id).put("width", width)
                    .put("height", height).put("density", dpi).put("refreshRate", display.display.refreshRate)
                    .put("independentFocus", true)
                    .put("localIme", localIme)
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
                    if (request.optString("action") == "preview") {
                        frameStore.setViewerVisible(request.optBoolean("visible"))
                        reply(JSONObject().put("ok", true).put("displayId", id))
                        continue
                    }
                    require(displayManager.getDisplay(id) != null) { "DISPLAY_GONE" }
                    require(allowOff || (power.isInteractive && !keyguard.isDeviceLocked)) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                    appRotation.synchronize(runningTasks().firstOrNull { taskDisplayId(it) == id })
                    val size = Point().also { display.display.getRealSize(it) }
                    val logicalWidth = size.x
                    val logicalHeight = size.y
                    val rotation = display.display.rotation
                    if (request.optString("action") in setOf("tap", "swipe", "long_press") && request.has("expectedWidth")) {
                        require(request.optInt("expectedWidth") == logicalWidth &&
                            request.optInt("expectedHeight") == logicalHeight &&
                            request.optInt("expectedRotation") == rotation) { "STALE_OBSERVATION" }
                    }
                    val result = when (request.getString("action")) {
                        "lifecycle" -> {
                            idleTimer.update(request.optInt("idleTimeoutMinutes", 20), request.optBoolean("activeRun"))
                            JSONObject().put("ok", true).put("displayId", id)
                        }
                        "probe" -> {
                            if (request.optBoolean("captureFingerprint")) frameStore.capture()
                            val top = runningTasks().firstOrNull { taskDisplayId(it) == id }?.topActivity
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("width", logicalWidth).put("height", logicalHeight).put("rotation", rotation)
                                .put("refreshRate", display.display.refreshRate)
                                .put("capture", frameStore.describe())
                                .put("packageName", top?.packageName.orEmpty())
                                .put("activity", top?.className.orEmpty()).apply {
                                    frameStore.currentFrame()?.let { frame ->
                                        put("frameId", frame.id).put("frameAgeMs", SystemClock.elapsedRealtime() - frame.id)
                                        put("frameFingerprint", frame.fingerprint.toString())
                                    }
                                    if (request.optBoolean("includeInputHealth")) {
                                        val health = runCatching {
                                            VirtualScreenInputHealth.parse(command(listOf("/system/bin/dumpsys", "input"),
                                                limit = 500_000, timeoutMs = 1500), id, top?.packageName.orEmpty())
                                        }.getOrDefault(VirtualScreenInputHealth.Result(VirtualScreenInputHealth.Status.UNKNOWN))
                                        put("input_health", JSONObject().put("status", health.status.name.lowercase())
                                            .put("display_id", id).put("window", health.window ?: JSONObject.NULL)
                                            .put("pid", health.pid ?: JSONObject.NULL))
                                    }
                                }
                        }
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
                        "observe" -> {
                            val ready = if (request.optBoolean("preview")) {
                                frameStore.setViewerVisible(true)
                                frameStore.currentFrame() ?: frameStore.capture()
                            } else frameStore.capture()
                            ready?.let { frame ->
                                JSONObject().put("ok", true).put("displayId", id)
                                    .put("width", frame.width).put("height", frame.height).put("rotation", frame.rotation)
                                    .put("frameId", frame.id)
                                    .put("frameFingerprint", frame.fingerprint.toString())
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
                        }

                        "launch", "restart" -> {
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
                            require(info.enabled && info.exported && resizable && info.launchMode <= ActivityInfo.LAUNCH_SINGLE_TASK) { "DISPLAY_APP_UNSUPPORTED" }
                            // Allow re-entry to our own display, but never migrate a user's task.
                            val runningElsewhere = runningTasks().any {
                                (it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName) && taskDisplayId(it) != id
                            }
                            val recovering = request.optString("action") == "restart"
                            if (recovering) {
                                require(!runningElsewhere && runningTasks().firstOrNull { taskDisplayId(it) == id }
                                    ?.topActivity?.packageName == packageName) { "UI_RECOVERY_TARGET_CHANGED" }
                            }
                            if (runningElsewhere || recovering) {
                                require(recovering || request.optBoolean("restartApp")) { "APP_ALREADY_RUNNING" }
                                require(packageName != "io.github.mangi.eta" && info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) { "APP_RESTART_UNSUPPORTED" }
                                if (recovering && allowOff && recoveryWakeLock == null) {
                                    recoveryWakeLock = createRecoveryWakeLock(power, display.display)
                                }
                                command(listOf("/system/bin/am", "force-stop", packageName))
                                require(runningTasks().none {
                                    it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName
                                }) { "APP_STOP_FAILED" }
                                if (recovering) {
                                    frameStore.invalidate()
                                    // Reattach the output after stopping the only app, including screen-off displays.
                                    display.surface = null
                                    display.surface = reader.surface
                                    // A sleeping display group cannot draw the cold-started activity.
                                    // The checked display-specific lock never targets the phone's group.
                                    recoveryWakeLock?.let { lock ->
                                        if (lock.isHeld) lock.release()
                                        lock.acquire()
                                    }
                                }
                            }
                            appRotation.apply(info.screenOrientation)
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
                            if (recovering) {
                                require(frameStore.capture(3_000L) != null) { "DISPLAY_FRAME_PENDING" }
                            }
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("component", component)
                                .put("restarted", runningElsewhere || recovering)
                        }

                        "tap" -> {
                            inputInjector.execute(
                                "tap",
                                integer(request, "x", 0, logicalWidth - 1).toString(),
                                integer(request, "y", 0, logicalHeight - 1).toString()
                            )
                        }

                        "text" -> textInput.insert(request.getString("text"), request.optBoolean("replace"))

                        "swipe" -> {
                            inputInjector.execute(
                                "swipe",
                                integer(request, "x1", 0, logicalWidth - 1).toString(),
                                integer(request, "y1", 0, logicalHeight - 1).toString(),
                                integer(request, "x2", 0, logicalWidth - 1).toString(),
                                integer(request, "y2", 0, logicalHeight - 1).toString(),
                                integer(request, "durationMs", 100, 2000).toString()
                            )
                        }

                        "back" -> {
                            inputInjector.execute("keyevent", "4")
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
                            if (code != null) inputInjector.execute("keyevent", code.toString())
                            else JSONObject().put("ok", true).put("displayId", id)
                        }

                        "long_press" -> {
                            val x = integer(request, "x", 0, logicalWidth - 1).toString()
                            val y = integer(request, "y", 0, logicalHeight - 1).toString()
                            inputInjector.execute("swipe", x, y, x, y, integer(request, "durationMs", 300, 3000).toString())
                        }

                        else -> failure("UNKNOWN_DISPLAY_ACTION")
                    }
                    if (request.optString("action") !in setOf("observe", "probe", "tasks", "lifecycle")) idleTimer.touch()
                    reply(result)
                } catch (error: Exception) {
                    reply(
                        failure(
                            if (error is IllegalArgumentException || error is IllegalStateException) error.message
                                ?: "DISPLAY_ACTION_FAILED" else "DISPLAY_ACTION_FAILED"
                        ).apply {
                            put("error_type", error.javaClass.simpleName)
                            if (request.optString("action") == "launch") {
                                put("component", request.optString("component"))
                                put("uri", request.optString("uri"))
                            }
                        }
                    )
                }
            }
        } catch (_: Exception) {
            reply(failure("ROOT_DISPLAY_UNAVAILABLE"))
        } finally {
            runCatching { rotationListener?.close() }
            runCatching { display?.release() }
            runCatching { capture?.close() }
            runCatching { reader?.close() }
            frames?.quitSafely()
            recoveryWakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
            wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        }
    }

    /** UID 0 only: refuse a shared/unknown group before creating a display-specific wake lock. */
    @SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    private fun createRecoveryWakeLock(power: PowerManager, display: Display): PowerManager.WakeLock {
        require(display.displayId > Display.DEFAULT_DISPLAY) { "DISPLAY_POWER_SCOPE_UNAVAILABLE" }
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getDeclaredConstructor().newInstance()
        require(Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info) == true &&
            infoClass.getField("displayGroupId").getInt(info) > 0) { "DISPLAY_POWER_SCOPE_UNAVAILABLE" }
        return PowerManager::class.java.getMethod(
            "newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType,
        ).invoke(power, PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Eta:virtual-recovery", display.displayId) as PowerManager.WakeLock
    }

    private fun flag(name: String) = DisplayManager::class.java.getField(name).getInt(null)

    /** Only the newly created secondary display may follow app orientation requests. */
    @SuppressLint("BlockedPrivateApi")
    private fun configureAppRotation(displayId: Int) {
        require(displayId > Display.DEFAULT_DISPLAY) { "INVALID_DISPLAY" }
        val service = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val type = Class.forName("android.view.IWindowManager")
        val disabled = type.getField("FIXED_TO_USER_ROTATION_DISABLED").getInt(null)
        type.getMethod("setFixedToUserRotation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, displayId, disabled)
        type.getMethod("setIgnoreOrientationRequest", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(service, displayId, false)
        require(type.getMethod("getIgnoreOrientationRequest", Int::class.javaPrimitiveType).invoke(service, displayId) == false) {
            "DISPLAY_ORIENTATION_UNAVAILABLE"
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun configureLocalIme(displayId: Int): Boolean = runCatching {
        val service = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val type = Class.forName("android.view.IWindowManager")
        type.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, displayId, 0)
        type.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType).invoke(service, displayId) == 0
    }.getOrDefault(false)

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

    private fun command(args: List<String>, limit: Int = 16_000, timeoutMs: Long = 10_000): String {
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
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
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
