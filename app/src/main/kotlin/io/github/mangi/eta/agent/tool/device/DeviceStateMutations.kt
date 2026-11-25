package io.github.mangi.eta.agent.tool.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.core.AgentLogger
import java.util.Locale
import org.json.JSONObject

/** 系统写操作与读回验证共用同一用户和同一状态口径。 */
internal class DeviceStateMutations(
    private val context: Context,
    private val logger: AgentLogger? = null,
    private val executeRoot: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun setSetting(args: JSONObject): String {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it.toString() }
        val namespace = args.optString("namespace").lowercase(Locale.ROOT)
        val key = args.optString("key")
        if (!DeviceToolContract.validSetting(namespace, key)) return invalid("设置命名空间或键格式无效")
        val value = args.optString("value")
        if (!args.has("value") || value.length > 2_000) return invalid("必须提供不超过 2000 字的设置值")
        val readCommand = "settings --user $userId get ${DeviceToolContract.quote(namespace)} ${DeviceToolContract.quote(key)}"
        val before = readSetting(readCommand)
        val result = executeRoot("settings --user $userId put ${DeviceToolContract.quote(namespace)} " +
            "${DeviceToolContract.quote(key)} ${DeviceToolContract.quote(value)}")
        val after = readSetting(readCommand)
        if (namespace == "system" && key in ROTATION_LOG_KEYS) {
            logger?.info(
                "Agent device mutation action=set_setting namespace=$namespace key=$key " +
                    "before=${before ?: "null"} after=${after ?: "null"} expected=$value ok=${result.ok}"
            )
        }
        return mutation("set_setting", userId, if (namespace == "global") "device" else "app_user", before, after, value, result)
            .put("namespace", namespace).put("key", key).toString()
    }

    fun setDeviceState(args: JSONObject): String {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it.toString() }
        val target = args.optString("target").lowercase(Locale.ROOT)
        val enabled = args.opt("enabled") as? Boolean ?: return invalid("enabled 必须是布尔值")
        if (target !in setOf("wifi", "bluetooth")) {
            return SystemStateControls(context, logger, executeRoot).set(target, enabled).toString()
        }
        val command = when (target) {
            "wifi" -> "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
            "bluetooth" -> "cmd bluetooth_manager ${if (enabled) "enable" else "disable"}"
            else -> return invalid("不支持的设备状态")
        }
        val before = readDeviceState(target)
        val result = executeRoot(command)
        val after = readDeviceState(target)
        return mutation("set_device_state", userId, "device", before, after, enabled, result)
            .put("target", target).toString()
    }

    fun appStateControl(args: JSONObject): String {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it.toString() }
        val packageName = args.optString("package_name")
        if (packageName == "android" || !DeviceToolContract.validPackageName(packageName)) {
            return DeviceToolContract.failure("INVALID_PACKAGE", "包名格式无效").toString()
        }
        val action = args.optString("action").lowercase(Locale.ROOT)
        val command = when (action) {
            "force_stop" -> "am force-stop --user $userId ${DeviceToolContract.quote(packageName)}"
            "freeze" -> "pm disable-user --user $userId ${DeviceToolContract.quote(packageName)}"
            "unfreeze" -> "pm enable --user $userId ${DeviceToolContract.quote(packageName)}"
            else -> return invalid("不支持的应用状态动作")
        }
        val before = readApplicationState(packageName, action)
            ?: return DeviceToolContract.failure("APP_STATE_UNAVAILABLE", "无法读取当前用户的应用状态；应用可能未安装、不可见或查询被拒绝，本次未执行")
                .put("user_id", userId).toString()
        val result = executeRoot(command)
        val after = readApplicationState(packageName, action)
        return mutation("app_state_control", userId, "app_user", before, after, action != "freeze", result)
            .put("package_name", packageName).put("action", action)
            .put("state_field", if (action == "force_stop") "stopped" else "enabled").toString()
    }

    fun setVolume(args: JSONObject): String {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it.toString() }
        val streamName = args.optString("stream").lowercase(Locale.ROOT)
        val stream = when (streamName) {
            "media" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice_call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> return invalid("不支持的音量通道")
        }
        val percent = args.optInt("percent", -1)
        if (percent !in 0..100) return invalid("percent 必须在 0 到 100 之间")
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return DeviceToolContract.failure("AUDIO_SERVICE_UNAVAILABLE", "系统音频服务不可用").toString()
        return try {
            val min = audio.getStreamMinVolume(stream)
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(min)
            val requested = (min + (max - min) * percent / 100.0).toInt().coerceIn(min, max)
            val before = audio.getStreamVolume(stream)
            audio.setStreamVolume(stream, requested, 0)
            val after = audio.getStreamVolume(stream)
            DeviceMutationResult.observed("set_volume", userId, "device", before, after, requested, true)
                .put("stream", streamName).put("percent", percent).put("level", after)
                .put("min_level", min).put("max_level", max).toString()
        } catch (_: SecurityException) {
            DeviceToolContract.failure("VOLUME_CHANGE_DENIED", "系统拒绝修改该音量通道").toString()
        } catch (_: RuntimeException) {
            DeviceToolContract.failure("VOLUME_CHANGE_FAILED", "音量修改或状态读回失败，结果尚未确认")
                .put("verified", false).toString()
        }
    }

    private fun readSetting(command: String): String? {
        val result = executeRoot(command)
        if (!result.ok || result.truncated) return null
        // settings CLI 用额外换行结束输出；只移除该换行，保留设置值自身的空格和换行。
        return result.stdout.removeSuffix("\n").takeUnless { it == "null" }
    }

    private fun readApplicationState(packageName: String, action: String): Boolean? = try {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        if (action == "force_stop") info.flags and ApplicationInfo.FLAG_STOPPED != 0 else info.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    internal fun readDeviceState(target: String): Boolean? = if (target == "wifi") {
        try {
            when (context.applicationContext.getSystemService(WifiManager::class.java)?.wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> true
                WifiManager.WIFI_STATE_DISABLED -> false
                else -> null
            }
        } catch (_: SecurityException) {
            null
        }
    } else {
        val result = executeRoot("dumpsys bluetooth_manager")
        if (result.ok) bluetoothState(result.stdout) else null
    }

    private fun mutation(
        tool: String,
        userId: Int,
        scope: String,
        before: Any?,
        after: Any?,
        expected: Any,
        result: BoundedRootCommandExecutor.Result,
    ): JSONObject = DeviceMutationResult.observed(
        tool, userId, scope, before, after, expected, result.ok,
        failureCode = if (result.ok) null else when {
            result.errorCode.isNotBlank() -> result.errorCode
            result.timedOut -> "ROOT_COMMAND_TIMEOUT"
            else -> "ROOT_COMMAND_FAILED"
        },
    ).put("exit_code", result.exitCode)

    private fun invalid(message: String): String = DeviceToolContract.failure("INVALID_ARGUMENT", message).toString()

    companion object {
        private val ROTATION_LOG_KEYS = setOf("accelerometer_rotation", "user_rotation")

        internal fun bluetoothState(output: String): Boolean? =
            when (Regex("(?m)^\\s*state:\\s*(ON|OFF|10|12)\\s*$").find(output)?.groupValues?.get(1)) {
                "ON", "12" -> true
                "OFF", "10" -> false
                else -> null
            }
    }
}
