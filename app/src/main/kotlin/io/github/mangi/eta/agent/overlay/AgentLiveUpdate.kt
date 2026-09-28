package io.github.mangi.eta.agent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/** 结果摘要保留的字符数，超出后在句子边界收尾。 */
private const val MAX_RESULT_CHARS = 160

/**
 * Android 16 实况通知（Live Updates / promoted ongoing notification）。
 *
 * Google 在 Android 16 引入以进度为中心的通知，系统会把满足条件的通知提升为实况通知，
 * 在状态栏以胶囊、锁屏与通知栏以卡片呈现。ColorOS 等 ROM 把它们接进各自的实时活动入口
 * （OPPO 侧即流体云），因此这里不需要任何私有 SDK 或 Hook。
 *
 * 提升条件（见 developer.android.com/develop/ui/views/notifications/live-update）：
 * 必须 ongoing、必须有 contentTitle、必须是 ProgressStyle 等允许的样式、
 * 不能自定义 contentView、不能是组摘要、不能 colorized、渠道重要度不能是 MIN，
 * 并声明 POST_PROMOTED_NOTIFICATIONS 权限。
 *
 * 仅服务前台操作期间使用：它对应「用户发起、正在进行、时间敏感」的一次 Agent 任务，
 * 符合实况通知的适用范围；任务结束即撤下，避免留下常驻通知。
 *
 * 通知同时承接原浮窗上的控制入口：暂停/继续、停止，以及内联输入的补充指令。
 */
internal object AgentLiveUpdate {

    private const val CHANNEL_ID = "eta_agent_live_update"
    private const val CHANNEL_NAME = "Agent 运行状态"
    private const val NOTIFICATION_ID = 4211

    const val ACTION_PAUSE = "io.github.mangi.eta.agent.runtime.PAUSE"
    const val ACTION_RESUME = "io.github.mangi.eta.agent.runtime.RESUME"
    const val ACTION_STOP = "io.github.mangi.eta.agent.runtime.STOP"
    const val ACTION_SUPPLEMENT = "io.github.mangi.eta.agent.runtime.SUPPLEMENT"
    const val EXTRA_SUPPLEMENT = "supplement_text"

    private const val REQUEST_PAUSE = 1
    private const val REQUEST_RESUME = 2
    private const val REQUEST_STOP = 3
    private const val REQUEST_SUPPLEMENT = 4
    private const val REQUEST_OPEN = 5
    private const val REQUEST_OPEN_ACTION = 7

    /** 状态栏胶囊位置很窄，超出部分由系统截断，这里先自行收敛长度。 */
    private const val MAX_TITLE_CHARS = 24
    private const val MAX_TEXT_CHARS = 120
    private const val MAX_PROGRESS = 100

    /** 正文跟随流式输出变化很快，限流后仍保证阶段变化（标题变化）立刻刷新。 */
    private const val MIN_REFRESH_INTERVAL_MS = 1_000L

    private var lastTitle: String? = null
    private var lastText: String? = null
    private var lastPublishAt = 0L
    private var promotionLogged = false

    /**
     * 判断当前设备是否可用：需要 Android 16 及以上，且用户没有关闭本应用的实况通知。
     * 这是耗时极短的跨进程查询，只在首次启用时调用一次。
     */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)
    }

    /**
     * 发布或刷新运行中的实况通知；不支持、被用户关闭或系统拒绝时静默跳过，不影响任务本身。
     * 点击通知（流体云胶囊或展开面板）回到 Eta 对应会话。
     */
    fun publish(context: Context, state: AgentOverlayState, target: AgentConversationTarget?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)) return

        val title = context.agentOverlayStatusText(state.status).take(MAX_TITLE_CHARS)
        val text = state.detailText.trim().replace('\n', ' ').take(MAX_TEXT_CHARS)
        val now = System.currentTimeMillis()
        val titleChanged = title != lastTitle
        if (!titleChanged) {
            if (text == lastText) return
            if (now - lastPublishAt < MIN_REFRESH_INTERVAL_MS) return
        }
        lastTitle = title
        lastText = text
        lastPublishAt = now

        runCatching {
            val notification = buildNotification(context, state.phase, title, text, target)
            manager.notify(NOTIFICATION_ID, notification)
            if (!promotionLogged) {
                promotionLogged = true
                logPromotionResult(manager, notification)
            }
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("agent_live_update_failed") {
                "Agent live update publish failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /**
     * 结束态：把结果概述留在流体云里，用户点一下就能进 Eta 看完整内容。
     * 状态自身已不再变化，因此不走限流，由 Service 决定何时撤下。
     */
    fun publishResult(context: Context, state: AgentOverlayState, target: AgentConversationTarget?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)) return

        val finished = state.phase != AgentOverlayPhase.FAILED
        val title = context.getString(
            if (finished) R.string.overlay_substatus_finished else R.string.overlay_substatus_failed,
        )
        val summary = summarizeAgentResult(state.detailText)
            .ifBlank { context.getString(R.string.overlay_result_ready) }
        lastTitle = title
        lastText = summary
        lastPublishAt = System.currentTimeMillis()

        runCatching {
            val builder = baseBuilder(context, title, target)
                .setContentText(summary)
                .setShortCriticalText(title)
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgress(if (finished) MAX_PROGRESS else 0)
                        .setProgressIndeterminate(false),
                )
            if (state.round > 0) {
                builder.setSubText(context.getString(R.string.overlay_result_rounds, state.round))
            }
            // 展开面板里给一个明确入口，语义与点击胶囊一致
            runCatching {
                builder.addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.overlay_open_conversation),
                        AgentAppLauncher.conversationPendingIntent(
                            context,
                            target,
                            REQUEST_OPEN_ACTION,
                        ),
                    ).build(),
                )
            }
            manager.notify(NOTIFICATION_ID, builder.build())
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("agent_live_update_result_failed") {
                "Agent live update result publish failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 任务结束或浮层撤下时同步撤掉实况通知。 */
    fun dismiss(context: Context) {
        lastTitle = null
        lastText = null
        lastPublishAt = 0L
        promotionLogged = false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildNotification(
        context: Context,
        phase: AgentOverlayPhase,
        title: String,
        text: String,
        target: AgentConversationTarget?,
    ): Notification {
        val builder = baseBuilder(context, title, target)
            .setContentText(text)
            // 胶囊态展示的文字，系统会按入口宽度自行裁剪
            .setShortCriticalText(title)
            .setStyle(
                Notification.ProgressStyle().setProgressIndeterminate(true),
            )

        if (phase == AgentOverlayPhase.RUNNING) {
            builder.addAction(
                controlAction(context, ACTION_PAUSE, REQUEST_PAUSE, R.string.overlay_pause),
            )
        } else if (phase == AgentOverlayPhase.PAUSED) {
            builder.addAction(
                controlAction(context, ACTION_RESUME, REQUEST_RESUME, R.string.overlay_resume),
            )
        }
        // 内联回复依赖可变 PendingIntent，个别 ROM 可能拒绝；失败时保留其余控制入口
        runCatching { builder.addAction(supplementAction(context)) }
        builder.addAction(controlAction(context, ACTION_STOP, REQUEST_STOP, R.string.action_stop))

        return builder.build()
    }

    /** 提升为实况通知所需的公共字段：ongoing、标题、可提升标记、渠道与点击入口。 */
    private fun baseBuilder(
        context: Context,
        title: String,
        target: AgentConversationTarget?,
    ): Notification.Builder {
        // 渠道重要度不能是 IMPORTANCE_MIN，否则通知不会被提升为实况通知
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setRequestPromotedOngoing(true)
            .setContentIntent(
                AgentAppLauncher.conversationPendingIntent(context, target, REQUEST_OPEN),
            )
    }

    private fun controlAction(
        context: Context,
        action: String,
        requestCode: Int,
        titleRes: Int,
    ): Notification.Action =
        Notification.Action.Builder(null, context.getString(titleRes), serviceIntent(context, action, requestCode))
            .build()

    /** 内联回复：流体云展开面板里直接输入补充指令，不需要重新打开应用。 */
    private fun supplementAction(context: Context): Notification.Action {
        val remoteInput = RemoteInput.Builder(EXTRA_SUPPLEMENT)
            .setLabel(context.getString(R.string.overlay_supplement_hint))
            .build()
        return Notification.Action.Builder(
            null,
            context.getString(R.string.overlay_supplement),
            serviceIntent(context, ACTION_SUPPLEMENT, REQUEST_SUPPLEMENT, mutable = true),
        ).addRemoteInput(remoteInput).build()
    }

    private fun serviceIntent(
        context: Context,
        action: String,
        requestCode: Int,
        mutable: Boolean = false,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AgentRuntimeService::class.java).setAction(action),
            flags,
        )
    }

    /**
     * 回读系统状态：FLAG_PROMOTED_ONGOING 为真说明这台设备（含 ROM）确实把通知提升成了实况通知。
     * 只在首次刷新时记录一次，供在 ColorOS 上确认流体云是否接住了标准 API。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun logPromotionResult(manager: NotificationManager, notification: Notification) {
        val promotable = notification.hasPromotableCharacteristics()
        val promoted = manager.activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }
            ?.notification
            ?.let { it.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
        AndroidAgentLogger.debug {
            "Agent live update posted: promotable=$promotable promoted=${promoted == true}"
        }
    }
}

/**
 * 结果摘要：流体云面板放不下整段回答，这里剥掉 Markdown 记号后取一段有信息量的文字，
 * 并尽量在句子边界收尾。完整内容留在会话里，点通知或入口按钮即可查看。
 */
internal fun summarizeAgentResult(text: String): String {
    val plain = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^[\\s>*+-]+"), " ")
        .replace(Regex("#{1,6}\\s*"), " ")
        .replace(Regex("\\*\\*|__|\\*|_"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (plain.length <= MAX_RESULT_CHARS) return plain
    val head = plain.take(MAX_RESULT_CHARS)
    val boundary = head.indexOfLast { it in "。！？；.!?;" }
    return if (boundary >= MAX_RESULT_CHARS / 2) {
        head.take(boundary + 1) + "…"
    } else {
        head.trimEnd() + "…"
    }
}
