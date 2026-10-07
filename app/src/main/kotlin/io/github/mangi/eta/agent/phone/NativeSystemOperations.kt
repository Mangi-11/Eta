package io.github.mangi.eta.agent.phone

import android.content.Context
import android.annotation.SuppressLint
import android.os.Process
import android.media.AudioManager
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import io.github.mangi.eta.agent.tool.DeviceMutationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal class NativeSystemOperations(
    private val context: Context,
    private val userId: Int,
    private val onMutation: () -> Unit,
) {
    fun execute(tool: String, args: JSONObject): JSONObject =
        when (tool) {
            "get_mobile_data",
            "set_mobile_data" -> mobileData(tool, args)
            "get_night_light",
            "set_night_light" -> nightLight(tool, args)
            "set_sound_mode" -> sound(args)
            "get_hotspot",
            "set_hotspot" -> hotspot(tool, args)
            else -> PhoneOperation.failure("UNKNOWN_TOOL", "未知系统操作")
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun mobileData(tool: String, args: JSONObject): JSONObject {
        if (Process.myUid() != 0) PhoneOperation.error("ROOT_REQUIRED", "移动数据操作需要 Root 身份")
        PhoneOperation.allowed(
            args,
            if (tool == "set_mobile_data") setOf("enabled") else emptySet(),
        )
        val subscription = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
        if (!android.telephony.SubscriptionManager.isValidSubscriptionId(subscription))
            PhoneOperation.error("MOBILE_DATA_UNAVAILABLE", "没有可用的默认数据 SIM 卡")
        val manager =
            context
                .getSystemService(android.telephony.TelephonyManager::class.java)
                .createForSubscriptionId(subscription)
        val before = manager.isDataEnabled
        if (tool == "get_mobile_data")
            return PhoneOperation.ok(tool)
                .put("enabled", before)
                .put("subscription_id", subscription)
        val expected = PhoneOperation.boolean(args, "enabled")
        if (before != expected) {
            onMutation()
            manager.isDataEnabled = expected
        }
        return DeviceMutationResult.observed(
                "set_device_state",
                userId,
                "default_data_subscription",
                before,
                manager.isDataEnabled,
                expected,
                true,
            )
            .put("target", "mobile_data")
            .put("subscription_id", subscription)
    }

    private fun nightLight(tool: String, args: JSONObject): JSONObject {
        PhoneOperation.allowed(
            args,
            if (tool == "set_night_light") setOf("enabled") else emptySet(),
        )
        val type = Class.forName("android.hardware.display.ColorDisplayManager")
        val available =
            type.getMethod("isNightDisplayAvailable", Context::class.java).invoke(null, context)
                as Boolean
        if (!available) PhoneOperation.error("DEVICE_UNSUPPORTED", "此设备未提供标准护眼模式接口")
        val manager =
            context.getSystemService("color_display")
                ?: PhoneOperation.error("DEVICE_UNSUPPORTED", "护眼服务不可用")
        val read = type.getMethod("isNightDisplayActivated")
        val before = read.invoke(manager) as Boolean
        if (tool == "get_night_light") return PhoneOperation.ok(tool).put("enabled", before)
        val expected = PhoneOperation.boolean(args, "enabled")
        var accepted = true
        if (before != expected) {
            onMutation()
            accepted =
                type
                    .getMethod("setNightDisplayActivated", Boolean::class.javaPrimitiveType)
                    .invoke(manager, expected) as Boolean
        }
        return DeviceMutationResult.observed(
                "set_device_state",
                userId,
                "app_user",
                before,
                read.invoke(manager),
                expected,
                accepted,
            )
            .put("target", "night_light")
    }

    private fun sound(args: JSONObject): JSONObject {
        PhoneOperation.allowed(args, setOf("mode"))
        val mode = PhoneOperation.text(args, "mode", true, 20)
        val expected =
            when (mode) {
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> PhoneOperation.error("INVALID_ARGUMENT", "响铃模式无效")
            }
        val audio =
            context.getSystemService(AudioManager::class.java)
                ?: PhoneOperation.error("AUDIO_UNAVAILABLE", "音频服务不可用")
        val before = audio.ringerMode
        if (before != expected) {
            onMutation()
            audio.ringerMode = expected
        }
        return DeviceMutationResult.observed(
                "set_sound_mode",
                userId,
                "device",
                before,
                audio.ringerMode,
                expected,
                true,
            )
            .put("mode", mode)
    }

    private fun hotspot(tool: String, args: JSONObject): JSONObject {
        PhoneOperation.allowed(args, if (tool == "set_hotspot") setOf("enabled") else emptySet())
        if (Build.VERSION.SDK_INT < 36)
            PhoneOperation.error("DEVICE_UNSUPPORTED", "当前系统未提供兼容的热点控制接口")
        val expected = if (tool == "set_hotspot") PhoneOperation.boolean(args, "enabled") else null
        val manager =
            context.getSystemService(TetheringManager::class.java)
                ?: PhoneOperation.error("HOTSPOT_UNAVAILABLE", "热点服务不可用")
        val executor = Executor { it.run() }
        val current = AtomicReference<Boolean?>(null)
        val initial = CountDownLatch(1)
        val event =
            object : TetheringManager.TetheringEventCallback {
                override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                    current.set(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
                    initial.countDown()
                }
            }
        manager.registerTetheringEventCallback(executor, event)
        try {
            initial.await(2, TimeUnit.SECONDS)
            val before = current.get()
            if (expected == null)
                return PhoneOperation.ok(tool)
                    .put("ok", before != null)
                    .put("enabled", before ?: JSONObject.NULL)
            if (before == expected)
                return DeviceMutationResult.observed(
                    tool,
                    userId,
                    "device",
                    before,
                    before,
                    expected,
                    true,
                )
            val request =
                TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
            val done = CountDownLatch(1)
            val accepted = AtomicReference<Boolean?>(null)
            onMutation()
            if (expected)
                manager.startTethering(
                    request,
                    executor,
                    object : TetheringManager.StartTetheringCallback {
                        override fun onTetheringStarted() {
                            accepted.set(true)
                            done.countDown()
                        }

                        override fun onTetheringFailed(error: Int) {
                            accepted.set(false)
                            done.countDown()
                        }
                    },
                )
            else
                manager.stopTethering(
                    request,
                    executor,
                    object : TetheringManager.StopTetheringCallback {
                        override fun onStopTetheringSucceeded() {
                            accepted.set(true)
                            done.countDown()
                        }

                        override fun onStopTetheringFailed(error: Int) {
                            accepted.set(false)
                            done.countDown()
                        }
                    },
                )
            done.await(5, TimeUnit.SECONDS)
            repeat(10) {
                if (current.get() != expected && !Thread.currentThread().isInterrupted)
                    Thread.sleep(100)
            }
            return DeviceMutationResult.observed(
                tool,
                userId,
                "device",
                before,
                current.get(),
                expected,
                accepted.get() == true,
            )
        } finally {
            manager.unregisterTetheringEventCallback(event)
        }
    }
}
