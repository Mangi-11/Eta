package io.github.mangi.eta.agent.tool

import android.content.Context
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject

/** 有明确系统语义的开关；不把模型提供的设置键或命令作为执行入口。 */
internal class SystemStateControls(
    private val context: Context,
    private val execute: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun read(target: String): Boolean? =
        try {
            when (target) {
                "airplane_mode" ->
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        0,
                    ) == 1
                "location" ->
                    context.getSystemService(LocationManager::class.java)?.isLocationEnabled
                "battery_saver" ->
                    context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
                "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
                "auto_rotate" -> settingBoolean("system", Settings.System.ACCELEROMETER_ROTATION)
                "auto_brightness" ->
                    settingBoolean("system", Settings.System.SCREEN_BRIGHTNESS_MODE)
                "dark_mode" ->
                    execute("cmd uimode night")
                        .takeIf { it.ok && !it.truncated }
                        ?.stdout
                        ?.let {
                            when {
                                Regex("(?i)night mode: yes").containsMatchIn(it) -> true
                                Regex("(?i)night mode: no").containsMatchIn(it) -> false
                                else -> null
                            }
                        }
                else -> null
            }
        } catch (_: SecurityException) {
            null
        }

    fun set(target: String, enabled: Boolean): JSONObject {
        val command =
            command(target, enabled, DeviceToolContract.appUserId(context))
                ?: return DeviceToolContract.failure("INVALID_ARGUMENT", "不支持的系统开关")
        if (target == "nfc" && NfcAdapter.getDefaultAdapter(context) == null)
            return DeviceToolContract.failure("DEVICE_UNSUPPORTED", "设备不支持 NFC")
        val before = read(target)
        if (before == enabled)
            return DeviceMutationResult.observed(
                    "set_device_state",
                    DeviceToolContract.appUserId(context),
                    "device",
                    before,
                    before,
                    enabled,
                    true,
                )
                .put("target", target)
        val result = execute(command)
        val after = read(target)
        return DeviceMutationResult.observed(
                "set_device_state",
                DeviceToolContract.appUserId(context),
                "device",
                before,
                after,
                enabled,
                result.ok,
                result.errorCode.takeIf(String::isNotBlank)
                    ?: if (result.timedOut) "STATE_CHANGE_UNCONFIRMED" else null,
            )
            .put("target", target)
    }

    private fun settingBoolean(namespace: String, key: String): Boolean? {
        val r =
            execute("settings --user ${DeviceToolContract.appUserId(context)} get $namespace $key")
        return if (!r.ok || r.truncated) null
        else
            when (r.stdout.trim()) {
                "1" -> true
                "0" -> false
                else -> null
            }
    }

    companion object {
        val targets =
            listOf(
                "wifi",
                "bluetooth",
                "mobile_data",
                "airplane_mode",
                "location",
                "nfc",
                "battery_saver",
                "dark_mode",
                "night_light",
            )

        fun command(target: String, enabled: Boolean, userId: Int): String? {
            if (userId < 0) return null
            val value = if (enabled) 1 else 0
            return when (target) {
                "airplane_mode" ->
                    "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"
                "location" -> "cmd location set-location-enabled $enabled --user $userId"
                "nfc" -> "cmd nfc ${if (enabled) "enable-nfc" else "disable-nfc persist"}"
                "battery_saver" -> "cmd power set-mode $value"
                "dark_mode" -> "cmd uimode night ${if (enabled) "yes" else "no"}"
                else -> null
            }
        }
    }
}
