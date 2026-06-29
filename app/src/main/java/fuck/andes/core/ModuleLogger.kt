package fuck.andes.core

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

internal class ModuleLogger(private val module: XposedModule) {
    private val throttledLogs = ConcurrentHashMap<String, Long>()

    fun debug(message: String) {
        if (!ModuleConfig.ENABLE_VERBOSE_LOGS) return
        module.log(Log.DEBUG, ModuleConfig.TAG, message)
    }

    fun info(message: String) {
        module.log(Log.INFO, ModuleConfig.TAG, message)
    }

    fun warn(message: String) {
        module.log(Log.WARN, ModuleConfig.TAG, message)
    }

    fun warnThrottled(
        key: String,
        message: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS
    ) {
        if (shouldLog(key, windowMs)) {
            warn(message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module.log(Log.ERROR, ModuleConfig.TAG, message)
        } else {
            module.log(Log.ERROR, ModuleConfig.TAG, message, throwable)
        }
    }

    fun errorThrottled(
        key: String,
        message: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS
    ) {
        if (!shouldLog(key, windowMs)) return
        error(message, throwable)
    }

    private fun shouldLog(key: String, windowMs: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        val previous = throttledLogs[key]
        if (previous != null && now - previous < windowMs) {
            return false
        }
        throttledLogs[key] = now
        return true
    }
}
