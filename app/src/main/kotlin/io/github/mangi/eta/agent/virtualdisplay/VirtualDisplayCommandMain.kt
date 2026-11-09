package io.github.mangi.eta.agent.virtualdisplay

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * 进程内 Surface 临时登记表。
 *
 * 当设备仅具 Root 而未挂载 system_server Hook 时，Eta 主进程将 ImageReader.surface
 * 以一次性随机 token 放入此表；特权子进程（UID 0 / 1000）通过受保护的 ContentProvider
 * 取出 Parcelable Surface 并在系统权限下创建受信任 VirtualDisplay。
 */
internal object VirtualDisplaySurfaceRegistry {
    private data class Entry(
        val surface: Surface,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val TOKEN_TTL_MS = 15_000L

    fun register(surface: Surface): String {
        pruneExpired()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(surface)
        return token
    }

    fun consume(token: String): Surface? {
        pruneExpired()
        val entry = entries.remove(token) ?: return null
        if (System.currentTimeMillis() - entry.createdAtMs > TOKEN_TTL_MS) return null
        return entry.surface.takeIf { it.isValid }
    }

    fun unregister(token: String) {
        entries.remove(token)
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, entry) -> now - entry.createdAtMs > TOKEN_TTL_MS }
    }
}

/**
 * Root 特权虚拟屏桥接入口（由 app_process 启动）。
 *
 * 支持两种模式：
 * 1. action="hold_display"：通过 ContentProvider 取得 Eta 进程的 Surface，以系统身份创建
 *    TRUSTED VirtualDisplay，输出 ETA_VD_READY:<displayId> 后保持存活；stdin 断开即自动释放。
 * 2. action="move_to_main"：查找指定 displayId 或 packageName 的 RootTask 并调用
 *    IActivityTaskManager.moveRootTaskToDisplay 迁移至物理主屏（DEFAULT_DISPLAY）。
 */
internal object VirtualDisplayCommandMain {
    const val READY_PREFIX = "ETA_VD_READY:"
    const val RESULT_PREFIX = "ETA_VD_RESULT:"

    // 静态强引用持有 VirtualDisplay 与 Surface，防止 ART 局部栈帧优化在等待期间提前触发 GC finalize 销毁虚拟屏。
    @Volatile
    private var retainedVirtualDisplay: android.hardware.display.VirtualDisplay? = null

    @Volatile
    private var retainedSurface: Surface? = null

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        try {
            val uid = Process.myUid()
            if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
                println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "ROOT_REQUIRED"))
                exitProcess(1)
            }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
            val firstLine = reader.readLine()?.trim().orEmpty()
            if (firstLine.isEmpty()) exitProcess(1)
            val request = JSONObject(firstLine)
            when (request.optString("action")) {
                "hold_display" -> holdVirtualDisplay(request, reader)
                "move_to_main" -> {
                    val result = moveTaskToMainDisplay(request)
                    println(RESULT_PREFIX + result.toString())
                    exitProcess(0)
                }
                else -> {
                    println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "UNKNOWN_ACTION"))
                    exitProcess(1)
                }
            }
        } catch (t: Throwable) {
            println(
                RESULT_PREFIX + JSONObject()
                    .put("ok", false)
                    .put("code", "BRIDGE_ERROR")
                    .put("type", t.javaClass.simpleName),
            )
            exitProcess(1)
        }
    }

    private fun holdVirtualDisplay(request: JSONObject, reader: BufferedReader) {
        if (!CanvasRoutingPolicy.supportsBackgroundDisplay(android.os.Build.VERSION.SDK_INT)) {
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "DISPLAY_ISOLATION_UNSUPPORTED"))
            exitProcess(1)
        }
        val userId = request.optInt("user_id", 0)
        val token = request.optString("surface_token")
        val width = request.optInt("width", 1080).coerceIn(320, 4096)
        val height = request.optInt("height", 2400).coerceIn(320, 4096)
        val densityDpi = request.optInt("density_dpi", 420).coerceIn(120, 960)

        val surface = acquireSurfaceFromApp(userId, token)
        if (surface == null || !surface.isValid) {
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "SURFACE_UNAVAILABLE"))
            exitProcess(1)
        }

        // 优先切换至 SYSTEM_UID (1000) 以通过 DisplayManagerService 对 ADD_TRUSTED_DISPLAY 与 CAPTURE_VIDEO_OUTPUT 的校验
        switchToSystemUidIfPossible()

        val virtualDisplay = createTrustedVirtualDisplay(surface, width, height, densityDpi)
        if (virtualDisplay == null) {
            surface.release()
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "CREATE_DISPLAY_FAILED"))
            exitProcess(1)
        }

        val displayId = virtualDisplay.display?.displayId ?: Display.INVALID_DISPLAY
        if (displayId <= 0) {
            runCatching { virtualDisplay.release() }
            surface.release()
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "INVALID_DISPLAY_ID"))
            exitProcess(1)
        }

        retainedVirtualDisplay = virtualDisplay
        retainedSurface = surface
        if (!CanvasRoutingPolicy.hasRequiredDisplayFlags(virtualDisplay.display.flags) ||
            !applyDisplayImePolicyHide(displayId)
        ) {
            releaseRetainedDisplay()
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "DISPLAY_ISOLATION_UNAVAILABLE"))
            exitProcess(1)
        }

        println("$READY_PREFIX$displayId")
        System.out.flush()

        val mainHandler = android.os.Handler(Looper.getMainLooper())
        Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "release") break
                }
            } catch (_: Throwable) {
                // 宿主关闭管道或取消任务
            } finally {
                mainHandler.post {
                    releaseRetainedDisplay()
                    exitProcess(0)
                }
            }
        }, "eta-vd-stdin-watcher").apply {
            isDaemon = true
            start()
        }

        try {
            Looper.loop()
        } finally {
            releaseRetainedDisplay()
        }
        exitProcess(0)
    }

    private fun releaseRetainedDisplay() {
        val vd = retainedVirtualDisplay
        val surf = retainedSurface
        retainedVirtualDisplay = null
        retainedSurface = null
        runCatching { vd?.release() }
        runCatching { surf?.release() }
    }

    @Suppress("DEPRECATION")
    private fun switchToSystemUidIfPossible() {
        runCatching {
            if (Process.myUid() == Process.ROOT_UID) {
                android.system.Os.setuid(Process.SYSTEM_UID)
            }
        }
    }

    private fun applyDisplayImePolicyHide(displayId: Int): Boolean = runCatching {
        if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) return@runCatching false
        switchToSystemUidIfPossible()
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val wms = wmgClass.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching false
        val setPolicy = wms.javaClass.methods.firstOrNull {
            it.name == "setDisplayImePolicy" && it.parameterCount == 2
        } ?: return@runCatching false
        setPolicy.invoke(wms, displayId, CanvasRoutingPolicy.DISPLAY_IME_POLICY_HIDE)
        val getPolicy = wms.javaClass.methods.firstOrNull {
            it.name == "getDisplayImePolicy" && it.parameterCount == 1
        }
        val actual = getPolicy?.invoke(wms, displayId) as? Int
        actual == CanvasRoutingPolicy.DISPLAY_IME_POLICY_HIDE
    }.getOrDefault(false)

    private fun acquireSurfaceFromApp(userId: Int, surfaceToken: String): Surface? {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getMethod("getService")
            .invoke(null) ?: return null
        val iActivityManager = Class.forName("android.app.IActivityManager")
        val iContentProvider = Class.forName("android.content.IContentProvider")
        val binderToken: IBinder = Binder()
        val holder = iActivityManager.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(
            activityManager,
            AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
            userId,
            binderToken,
            "EtaVirtualDisplay",
        ) ?: return null

        return try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val attribution = android.content.AttributionSource.Builder(Process.myUid())
                .setPackageName("root")
                .build()
            val extras = AccessibilityProtectionProtocol.request().apply {
                putString(AccessibilityProtectionProtocol.EXTRA_SURFACE_TOKEN, surfaceToken)
            }
            val callMethod = iContentProvider.methods.firstOrNull {
                it.name == "call" && it.parameterCount == 5
            } ?: return null
            val reply = callMethod.invoke(
                provider,
                attribution,
                AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
                AccessibilityProtectionProtocol.VIRTUAL_DISPLAY_SURFACE_METHOD,
                null,
                extras,
            ) as? Bundle ?: return null
            reply.classLoader = Surface::class.java.classLoader
            reply.getParcelable(AccessibilityProtectionProtocol.EXTRA_SURFACE, Surface::class.java)
        } finally {
            runCatching {
                iActivityManager.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(
                    activityManager,
                    AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
                    binderToken,
                    userId,
                )
            }
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun createTrustedVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): android.hardware.display.VirtualDisplay? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.hardware.display.VirtualDisplayConfig.Builder(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
            )
                .setSurface(surface)
                .setFlags(CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
                .build()
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)
            val createMethod = dmgClass.methods.firstOrNull {
                it.name == "createVirtualDisplay" &&
                    it.parameterCount >= 3 &&
                    it.parameterTypes[0] == android.content.Context::class.java &&
                    it.parameterTypes[2] == android.hardware.display.VirtualDisplayConfig::class.java
            }
            if (createMethod != null) {
                val args = arrayOfNulls<Any?>(createMethod.parameterCount)
                args[0] = systemContext
                args[1] = null // MediaProjection
                args[2] = config
                createMethod.invoke(dmg, *args) as? android.hardware.display.VirtualDisplay
            } else {
                val dm = systemContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                dm?.createVirtualDisplay(config)
            }
        } else {
            val dm = systemContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            dm?.createVirtualDisplay(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS,
            )
        }
    }.getOrNull()

    private fun moveTaskToMainDisplay(request: JSONObject): JSONObject {
        val displayId = request.optInt("display_id", Display.INVALID_DISPLAY)
        val targetPackage = request.optString("package_name").trim()
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val service = atmClass.getMethod("getService").invoke(null)
            ?: return JSONObject().put("ok", false).put("code", "ATM_UNAVAILABLE")
        val serviceClass = service.javaClass

        val getTasks = serviceClass.methods.firstOrNull {
            it.name == "getTasks" && it.parameterCount >= 1
        }
        val rawTasks = runCatching {
            when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 50)
                3 -> getTasks.invoke(service, 50, false, false)
                4 -> getTasks.invoke(service, 50, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*>
        }.getOrNull().orEmpty()

        var matchedTaskId = -1
        for (item in rawTasks) {
            val taskInfo = item as? android.app.TaskInfo ?: continue
            val taskDisplayId = runCatching {
                android.app.TaskInfo::class.java.getField("displayId").getInt(taskInfo)
            }.getOrDefault(Display.INVALID_DISPLAY)
            val topPkg = taskInfo.topActivity?.packageName
                ?: taskInfo.baseActivity?.packageName
                ?: taskInfo.baseIntent.component?.packageName
                ?: taskInfo.baseIntent.`package`
            val displayMatches = displayId > 0 && taskDisplayId == displayId
            val pkgMatches = targetPackage.isNotEmpty() && topPkg == targetPackage
            if (displayMatches || pkgMatches) {
                matchedTaskId = taskInfo.taskId
                if (displayMatches && pkgMatches) break
            }
        }

        if (matchedTaskId <= 0) {
            return JSONObject().put("ok", false).put("code", "TASK_NOT_FOUND")
        }

        val moveMethod = serviceClass.methods.firstOrNull {
            it.name == "moveRootTaskToDisplay" && it.parameterCount == 2
        } ?: return JSONObject().put("ok", false).put("code", "MOVE_METHOD_NOT_FOUND")

        moveMethod.invoke(service, matchedTaskId, Display.DEFAULT_DISPLAY)
        runCatching {
            serviceClass.methods.firstOrNull {
                it.name == "setFocusedRootTask" && it.parameterCount == 1
            }?.invoke(service, matchedTaskId)
        }
        return JSONObject()
            .put("ok", true)
            .put("task_id", matchedTaskId)
            .put("target_display_id", Display.DEFAULT_DISPLAY)
    }
}
