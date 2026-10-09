package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.Display
import java.util.concurrent.ConcurrentHashMap

/** UID 0 helper only. System task callbacks are applied only to the owned display's top task. */
@SuppressLint("BlockedPrivateApi")
internal class VirtualScreenAppRotation(private val display: Display, private val naturalLandscape: Boolean) : AutoCloseable {
    private val changes = ConcurrentHashMap<Int, Int>()
    private val taskService = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val taskServiceClass = Class.forName("android.app.IActivityTaskManager")
    private val listenerClass = Class.forName("android.app.ITaskStackListener")
    private val stubClass = Class.forName("android.app.ITaskStackListener\$Stub")
    private val activityChanged = transaction("onActivityRequestedOrientationChanged")
    private val taskChanged = transaction("onTaskRequestedOrientationChanged")
    private val windowService = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
    private val windowClass = Class.forName("android.view.IWindowManager")
    // Android 14 uses two parameters; newer platform versions also require a caller label.
    private val freezeRotation = try {
        windowClass.getMethod("freezeDisplayRotation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    } catch (_: NoSuchMethodException) {
        windowClass.getMethod("freezeDisplayRotation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private var lastComponent: ComponentName? = null
    private val binder = object : Binder() {
        init { attachInterface(null, "android.app.ITaskStackListener") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() !in setOf(0, 1000)) return false
            data.enforceInterface("android.app.ITaskStackListener")
            if (code == activityChanged || code == taskChanged) {
                val taskId = data.readInt()
                val orientation = data.readInt()
                if (changes.size > 128) changes.clear()
                changes[taskId] = orientation
            }
            return true
        }
    }
    private val listener = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

    init {
        require(display.displayId > Display.DEFAULT_DISPLAY) { "INVALID_DISPLAY" }
        taskServiceClass.getMethod("registerTaskStackListener", listenerClass).invoke(taskService, listener)
    }

    fun synchronize(top: ActivityManager.RunningTaskInfo?) {
        if (top == null) return
        require(android.app.TaskInfo::class.java.getField("displayId").getInt(top) == display.displayId) { "DISPLAY_OWNER_MISMATCH" }
        val orientation = changes.remove(top.taskId)
        if (lastComponent != top.topActivity) {
            lastComponent = top.topActivity
            val activity = android.app.TaskInfo::class.java.getField("topActivityInfo").get(top) as? ActivityInfo
            apply(activity?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
        orientation?.let(::apply)
    }

    fun apply(orientation: Int) {
        require(display.displayId > Display.DEFAULT_DISPLAY) { "INVALID_DISPLAY" }
        val rotation = rotationFor(orientation, naturalLandscape, display.rotation)
        if (rotation == display.rotation) return
        if (freezeRotation.parameterCount == 3) {
            freezeRotation.invoke(windowService, display.displayId, rotation, "Eta virtual task")
        } else {
            freezeRotation.invoke(windowService, display.displayId, rotation)
        }
    }

    override fun close() {
        taskServiceClass.getMethod("unregisterTaskStackListener", listenerClass).invoke(taskService, listener)
        changes.clear()
    }

    private fun transaction(name: String): Int = stubClass.getDeclaredField("TRANSACTION_$name").apply {
        isAccessible = true
    }.getInt(null)

    companion object {
        fun rotationFor(orientation: Int, naturalLandscape: Boolean, currentRotation: Int): Int = when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> if (naturalLandscape) 0 else 1
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> if (naturalLandscape) 1 else 0
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> if (naturalLandscape) 2 else 3
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> if (naturalLandscape) 3 else 2
            ActivityInfo.SCREEN_ORIENTATION_LOCKED, ActivityInfo.SCREEN_ORIENTATION_BEHIND -> currentRotation
            else -> 0
        }
    }
}
