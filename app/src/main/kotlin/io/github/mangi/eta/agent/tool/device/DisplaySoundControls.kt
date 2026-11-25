package io.github.mangi.eta.agent.tool.device

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.phone.PhoneOperation
import kotlin.math.abs
import org.json.JSONObject

internal class DisplaySoundControls(
    private val context: Context,
    private val execute: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun sound(): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("ok", audio != null)
            .put("tool", "get_sound_state")
            .put(
                "ringer_mode",
                when (audio?.ringerMode) {
                    0 -> "silent"
                    1 -> "vibrate"
                    2 -> "normal"
                    else -> JSONObject.NULL
                },
            )
            .put(
                "do_not_disturb",
                context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                    ?: JSONObject.NULL,
            )
            .put(
                "streams",
                JSONObject().also { streams ->
                    mapOf(
                            "media" to 3,
                            "ring" to 2,
                            "notification" to 5,
                            "alarm" to 4,
                            "voice_call" to 0,
                            "system" to 1,
                        )
                        .forEach { (name, stream) ->
                            if (audio != null)
                                streams.put(
                                    name,
                                    JSONObject()
                                        .put("level", audio.getStreamVolume(stream))
                                        .put("max_level", audio.getStreamMaxVolume(stream)),
                                )
                        }
                },
            )
    }

    fun display(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("tool", "get_display_state")
            .put("brightness_percent", brightness() ?: JSONObject.NULL)
            .put(
                "auto_brightness",
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    0,
                ) == 1,
            )
            .put(
                "auto_rotate",
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0,
                ) == 1,
            )
            .put(
                "screen_timeout_seconds",
                Settings.System.getLong(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    0,
                ) / 1000,
            )

    fun brightness(args: JSONObject): JSONObject {
        val percent = PhoneOperation.integer(args, "percent", 0, 100).toInt()
        val before =
            brightness()
                ?: return DeviceToolContract.failure("BRIGHTNESS_UNAVAILABLE", "此系统不支持可靠的百分比亮度接口")
        val result = execute("cmd display set-brightness $percent --unit percentage")
        val after = brightness()
        val verified = result.ok && after != null && abs(after - percent) <= 1.0
        return DeviceMutationResult.observed(
                "set_brightness",
                DeviceToolContract.appUserId(context),
                "device",
                before,
                after,
                percent,
                result.ok,
                matchesExpected = verified,
            )
            .put("actual_percent", after ?: JSONObject.NULL)
    }

    fun timeout(args: JSONObject): JSONObject {
        val seconds = PhoneOperation.integer(args, "seconds", 15, 1800)
        val key = Settings.System.SCREEN_OFF_TIMEOUT
        val before = Settings.System.getLong(context.contentResolver, key, -1)
        val result =
            execute(
                "settings --user ${DeviceToolContract.appUserId(context)} put system $key ${seconds * 1000}"
            )
        val after = Settings.System.getLong(context.contentResolver, key, -1)
        return DeviceMutationResult.observed(
            "set_screen_timeout",
            DeviceToolContract.appUserId(context),
            "app_user",
            before,
            after,
            seconds * 1000,
            result.ok,
        )
    }

    fun dnd(args: JSONObject): JSONObject {
        val mode = PhoneOperation.text(args, "mode", true, 20)
        val expected =
            mapOf("off" to 0, "priority" to 1, "silent" to 2, "alarms" to 3)[mode]
                ?: PhoneOperation.error("INVALID_ARGUMENT", "勿扰模式无效")
        val command =
            mapOf(
                    "off" to "all",
                    "priority" to "priority",
                    "silent" to "none",
                    "alarms" to "alarms",
                )
                .getValue(mode!!)
        val before = Settings.Global.getInt(context.contentResolver, "zen_mode", -1)
        val result = if (before == expected) null else execute("cmd notification set_dnd $command")
        val after = Settings.Global.getInt(context.contentResolver, "zen_mode", -1)
        return DeviceMutationResult.observed(
            "set_do_not_disturb",
            DeviceToolContract.appUserId(context),
            "device",
            before,
            after,
            expected,
            result?.ok ?: true,
        )
    }

    private fun brightness(): Double? {
        val result = execute("cmd display get-brightness --unit percentage")
        return if (result.ok && !result.truncated)
            result.stdout.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        else null
    }
}
