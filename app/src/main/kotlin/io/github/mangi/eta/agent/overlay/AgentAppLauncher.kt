package io.github.mangi.eta.agent.overlay

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.MainActivity

/**
 * 从实况通知（流体云胶囊、展开面板、结果态入口）回到 Eta 会话。
 *
 * 通知由后台应用发布，属于后台启动 Activity 的受限场景；这里统一用 PendingIntent
 * 并声明后台启动白名单参数，避免点击后被系统静默拦截。带上产生该任务的会话 key，
 * 点进 Eta 直接落到对应会话，而不是停在首页。
 */
internal object AgentAppLauncher {

    /** 会话来源 extra：不同来源的会话 id 规则不同，需由 Eta 侧按 source 解析。 */
    const val EXTRA_CONVERSATION_SOURCE =
        "io.github.mangi.eta.agent.overlay.extra.CONVERSATION_SOURCE"

    @Suppress("DEPRECATION")
    fun conversationPendingIntent(
        context: Context,
        target: AgentConversationTarget?,
        requestCode: Int,
    ): PendingIntent {
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            conversationIntent(context, target),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
    }

    private fun conversationIntent(context: Context, target: AgentConversationTarget?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .apply {
                if (target != null) {
                    putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, target.key)
                    putExtra(EXTRA_CONVERSATION_SOURCE, target.source)
                }
            }
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
}

/**
 * 通知回跳目标：`source` 决定 Eta 侧按哪套规则定位会话，`key` 是该规则下的标识。
 * 聊天入口的 key 就是聊天会话 id；小布/小爱/语音等外部入口的 key 是其归档 payload
 * 里的 conversationKey，必须配合 source 才能算出归档会话 id。
 */
internal data class AgentConversationTarget(
    val source: String,
    val key: String,
)
