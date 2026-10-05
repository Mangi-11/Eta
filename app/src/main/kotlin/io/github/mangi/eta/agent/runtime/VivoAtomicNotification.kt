package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.app.PendingIntent
import io.github.mangi.eta.R

/** vivo 官方本地原子通知协议；只有开发者平台确认的 scene 才发布扩展。 */
internal object VivoAtomicNotification {
    fun extras(
        context: Context,
        title: String,
        tool: String,
        open: PendingIntent,
        scene: String,
        sequence: Int
    ): Bundle {
        if (!Build.MANUFACTURER.equals(
                "vivo",
                ignoreCase = true
            ) || !Regex("[A-Z][A-Z0-9_]{0,63}").matches(scene)
        ) return Bundle()
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", title)
            putCharSequence("notification.superx.baseInfos.content", tool)
            putInt("notification.superx.baseInfos.subInfo", 6)
        }
        val short = Bundle().apply {
            putParcelable("notification.superx.shortInfos.image", icon)
            putParcelable("notification.superx.shortInfos.imageClickResp", open)
            putString("notification.superx.shortInfos.describeShort", title)
            putString("notification.superx.shortInfos.coreInfoShort", tool.take(20))
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putBundle("island.superx.leftInfo", Bundle().apply {
                putParcelable("island.superx.leftInfo.icon", icon)
                putCharSequence("island.superx.leftInfo.content", "Eta")
            })
            putInt("island.superx.rightTemplate", 4)
            putBundle(
                "island.superx.rightInfo",
                Bundle().apply {
                    putCharSequence(
                        "island.superx.rightInfo.content",
                        tool.take(24)
                    )
                })
            putInt("island.superx.islandClick", 1)
            putParcelable("island.superx.clickResp", open)
        }
        return Bundle().apply {
            putInt("notification.superx.operation", if (sequence == 0) 0 else 1)
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", 4)
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.infos", Bundle())
            putBundle("notification.superx.shortInfos", short)
            putBundle("notification.superx.island", island)
            putParcelable("notification.superx.clickResp", open)
            putString("notification.superx.scene", scene)
            putInt("notification.superx.changedRecord", sequence)
            putInt("notification.superx.displays", 0x111)
            putBoolean("notification.superx.sound", false)
            putBoolean("notification.superx.dismissWhenKill", true)
        }
    }
}
