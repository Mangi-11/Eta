package io.github.mangi.eta.agent.display

import android.content.ClipData
import android.content.ClipDescription
import android.annotation.SuppressLint
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.IBinder
import android.os.Process
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Unicode fallback for displays without accessible editors. Restore the clipboard only if still ours. */
internal class VirtualScreenRootTextInput(private val displayId: Int) {

    fun insert(text: String, replace: Boolean): JSONObject {
        require(text.length <= 20_000 && '\u0000' !in text) { "INVALID_TEXT" }
        val apk = requireNotNull(System.getenv("CLASSPATH")) { "VIRTUAL_TEXT_UNAVAILABLE" }
        val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
        val process = ProcessBuilder("su", "2000", "-c",
            "CLASSPATH=$quotedApk app_process /system/bin ${VirtualScreenRootTextInput::class.java.name}")
            .redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { input ->
                input.write(JSONObject().put("displayId", displayId).put("text", text).put("replace", replace).toString())
            }
            var result: JSONObject? = null
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith(MARKER)) result = JSONObject(line.removePrefix(MARKER))
                }
            }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "VIRTUAL_TEXT_UNAVAILABLE" }
            return result ?: error("VIRTUAL_TEXT_UNAVAILABLE")
        } finally { process.destroy() }
    }

    private fun paste(text: String, replace: Boolean): JSONObject {
        val clipboard = ShellClipboard()
        val previous = try { clipboard.primaryClip } catch (error: Exception) { throw TextInputFailure("clipboard_read", error) }
        val label = "eta-virtual-${java.util.UUID.randomUUID()}"
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        try { clipboard.setPrimaryClip(clip) } catch (error: Exception) { throw TextInputFailure("clipboard_write", error) }
        try {
            require(clipboard.primaryClip?.description?.label == label) { "VIRTUAL_TEXT_UNAVAILABLE" }
            if (replace) key("keycombination", "113", "29")
            if (text.isEmpty()) {
                if (replace) key("keyevent", "67")
            } else key("keyevent", "279")
            // Paste is delivered asynchronously to the target editor.
            SystemClock.sleep(250)
            return JSONObject().put("ok", true).put("display_id", displayId)
                .put("method", "root_display_paste").put("verified", false)
                .put("requires_observation", true)
        } finally {
            if (clipboard.primaryClip?.description?.label == label) {
                if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }

    companion object {
        private const val MARKER = "ETA_VIRTUAL_TEXT:"

        @JvmStatic
        @Suppress("DEPRECATION")
        fun main(args: Array<String>) {
            var stage = "identity"
            val result = try {
                check(Process.myUid() == 2000) { "SHELL_IDENTITY_REQUIRED" }
                Thread {
                    SystemClock.sleep(10_000)
                    Process.killProcess(Process.myPid())
                }.apply { isDaemon = true; start() }
                // Start with the shell UID before Binder opens; changing UID afterward keeps stale Binder credentials.
                stage = "request"
                val bytes = System.`in`.readNBytes(128_001)
                require(bytes.size <= 128_000) { "INVALID_TEXT" }
                val request = JSONObject(bytes.toString(Charsets.UTF_8))
                val id = request.getInt("displayId")
                require(id > 0) { "INVALID_DISPLAY" }
                val text = request.getString("text")
                require(text.length <= 20_000 && '\u0000' !in text) { "INVALID_TEXT" }
                stage = "paste"
                VirtualScreenRootTextInput(id).paste(text, request.optBoolean("replace"))
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("code", "VIRTUAL_TEXT_UNAVAILABLE")
                    .put("error_type", (error.cause ?: error).javaClass.simpleName)
                    .put("error_stage", if (error is TextInputFailure) error.stage else stage)
                    .put("helper_uid", Process.myUid())
                    .apply {
                        (error.cause as? ClipboardMethodUnavailable)?.let { put("clipboard_signature", it.signature) }
                    }
            }
            System.out.println(MARKER + result)
            System.out.flush()
            kotlin.system.exitProcess(0)
        }
    }

    private class TextInputFailure(val stage: String, cause: Exception) : Exception(cause)
    private class ClipboardMethodUnavailable(val signature: String) : Exception()

    @SuppressLint("BlockedPrivateApi")
    private class ShellClipboard {
        private val type = Class.forName("android.content.IClipboard")
        private val service = Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard"))

        val primaryClip: ClipData? get() = call("getPrimaryClip") as ClipData?
        fun setPrimaryClip(clip: ClipData) { call("setPrimaryClip", clip) }
        fun clearPrimaryClip() { call("clearPrimaryClip") }

        private fun call(name: String, clip: ClipData? = null): Any? {
            val prefix = if (name == "setPrimaryClip") listOf(clip) else emptyList()
            val methods = type.methods.filter { it.name == name }
            val candidates = methods.mapNotNull { method ->
                val parameters = method.parameterTypes.drop(prefix.size).map { it.simpleName }
                val arguments: List<Any?> = when (parameters) {
                    listOf("String", "String", "int") -> listOf("com.android.shell", null, 0)
                    listOf("String", "String", "int", "int") -> listOf("com.android.shell", null, 0, 0)
                    listOf("String", "String", "int", "int", "boolean") -> listOf("com.android.shell", null, 0, 0, true)
                    listOf("String", "String", "int", "int", "String") -> listOf("com.android.shell", null, 0, 0, null)
                    // OriginOS extends reads with three optional attribution strings and userOperate.
                    listOf("String", "String", "String", "String", "int", "int", "boolean") ->
                        if (name == "getPrimaryClip") listOf("com.android.shell", null, null, null, 0, 0, true) else return@mapNotNull null
                    else -> return@mapNotNull null
                }
                method to (prefix + arguments)
            }
            val (method, values) = candidates.maxByOrNull { it.first.parameterCount }
                ?: throw ClipboardMethodUnavailable(name + ":" + methods.joinToString(";") {
                    it.parameterTypes.joinToString(",") { parameter -> parameter.simpleName }
                })
            return try { method.invoke(service, *values.toTypedArray()) }
            catch (error: java.lang.reflect.InvocationTargetException) { throw (error.cause ?: error) }
        }
    }

    private fun key(vararg args: String) {
        val process = ProcessBuilder(listOf("/system/bin/input", "keyboard", "-d", displayId.toString()) + args)
            .redirectErrorStream(true).start()
        process.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("DISPLAY_ACTION_TIMEOUT")
        }
        check(process.exitValue() == 0) { "VIRTUAL_TEXT_UNAVAILABLE" }
    }
}
