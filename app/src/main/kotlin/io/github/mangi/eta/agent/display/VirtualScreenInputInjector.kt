package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/** Root-only injection preserves the framework result that the input shell command discards. */
internal class VirtualScreenInputInjector(
    private val displayId: Int,
    private val inputTimeoutMs: Long = 8_000,
    private val dispatch: ((InputEvent, Boolean) -> Boolean)? = null,
) {
    init {
        require(displayId > 0) { "INVALID_DISPLAY" }
        require(inputTimeoutMs in 1..8_000) { "INVALID_INPUT_TIMEOUT" }
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eta-virtual-input").apply { isDaemon = true }
    }

    fun execute(vararg args: String): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val future = executor.submit<Boolean> {
            when (args[0]) {
                "keyevent" -> key(args[1].toInt())
                "tap" -> touch(args[1].toFloat(), args[2].toFloat())
                "swipe" -> touch(args[1].toFloat(), args[2].toFloat(), args[3].toFloat(), args[4].toFloat(), args[5].toInt())
                else -> error("INVALID_INPUT_ACTION")
            }
        }
        val finished = try {
            future.get(inputTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return JSONObject().put("ok", false).put("code", "UI_INPUT_TIMEOUT")
                .put("message", "虚拟屏输入未在时限内完成，动作可能已经执行；已暂停自动操作，请确认当前应用状态。")
                .put("effect", "unconfirmed").put("input_finished", false)
                .put("input_elapsed_ms", SystemClock.elapsedRealtime() - started)
        }
        return JSONObject().put("ok", finished).put("displayId", displayId)
            .put("input_finished", finished).put("input_elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("effect", "unconfirmed").apply {
                if (!finished) put("code", "INPUT_DISPATCH_UNCONFIRMED")
                    .put("message", "系统未确认输入处理完成；可能是目标窗口、输入状态或响应问题。请重新观察，勿直接重复有副作用的操作。")
            }
    }

    private fun key(code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        var released = false
        try {
            val down = inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            if (Thread.currentThread().isInterrupted) return false
            val up = inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            released = true
            return down && up
        } finally {
            if (!released) runCatching { inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), wait = false) }
        }
    }

    private fun touch(x: Float, y: Float, endX: Float = x, endY: Float = y, durationMs: Int = 0): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var released = false
        try {
            var finished = motion(downTime, MotionEvent.ACTION_DOWN, x, y)
            if (durationMs > 0) {
                while (SystemClock.uptimeMillis() - downTime < durationMs) {
                    if (Thread.currentThread().isInterrupted) return false
                    val fraction = ((SystemClock.uptimeMillis() - downTime).toFloat() / durationMs).coerceIn(0f, 1f)
                    finished = motion(downTime, MotionEvent.ACTION_MOVE, x + (endX - x) * fraction,
                        y + (endY - y) * fraction) && finished
                    Thread.sleep(8)
                }
            }
            if (Thread.currentThread().isInterrupted) return false
            val up = motion(downTime, MotionEvent.ACTION_UP, endX, endY)
            released = true
            return up && finished
        } finally {
            if (!released) runCatching { motion(downTime, MotionEvent.ACTION_CANCEL, x, y, wait = false) }
        }
    }

    private fun motion(down: Long, action: Int, x: Float, y: Float, wait: Boolean = true): Boolean {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { inject(event, wait) } finally { event.recycle() }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun inject(event: InputEvent, wait: Boolean = true): Boolean {
        dispatch?.let { return it(event, wait) }
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        if (event is KeyEvent) event.source = InputDevice.SOURCE_KEYBOARD
        val type = Class.forName("android.hardware.input.InputManagerGlobal")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
        return type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, event, if (wait) 2 else 0) as Boolean
    }
}
