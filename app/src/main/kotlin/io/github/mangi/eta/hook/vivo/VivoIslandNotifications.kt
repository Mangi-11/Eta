package io.github.mangi.eta.hook.vivo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.EtaInjectedStrings
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID

/** Publishes the 68503 Copilot task island protocol from the injected process. */
internal class VivoIslandNotifications(
    context: Context,
    private val logger: AgentLogger,
    private val onStop: (String) -> Unit,
    private val sceneEnabled: (NotificationManager) -> Boolean = ::isSceneEnabled,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var lastPublishedAt = 0L
    private var failureLogged = false
    private val flush = Runnable { session?.takeUnless { it.hidden || it.terminal }?.let(::publish) }
    private val expire = Runnable { session?.takeIf { it.terminal }?.let(::hide) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = session ?: return
            if (intent.getStringExtra(EXTRA_TOKEN) != current.token ||
                intent.getStringExtra(EXTRA_RUN_ID) != current.runId || current.hidden
            ) return
            when (intent.action) {
                ACTION_STOP -> if (!current.terminal) onStop(current.runId)
                ACTION_DISMISS -> hide(current)
            }
        }
    }

    init {
        this.context.registerReceiver(
            receiver, IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(ACTION_DISMISS)
                addDataScheme("eta-vivo-island")
            }, Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun start(runId: String, conversationKey: String) = onMain {
        session?.let(::hide)
        main.removeCallbacks(expire)
        session = Session(runId, conversationKey, detail = text(
            R.string.injected_vivo_island_starting, "Preparing your task",
        )).also(::publish)
    }

    fun update(runId: String, event: AgentEvent) {
        val detail = when (event) {
            is AgentEvent.RunStarted, is AgentEvent.RoundStarted,
            is AgentEvent.ProviderRequestStarted, is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolFinished, is AgentEvent.HostedToolFinished ->
                text(R.string.injected_vivo_island_thinking, "Thinking")
            is AgentEvent.ToolStarted -> toolDetail(event.name)
            is AgentEvent.HostedToolStarted -> toolDetail(event.name)
            is AgentEvent.ModelRetryScheduled -> text(R.string.injected_vivo_island_retrying, "Retrying the model request")
            is AgentEvent.ContextCompaction -> if (event.phase == AgentEvent.ContextCompaction.PHASE_STARTED) {
                text(R.string.injected_vivo_island_compacting, "Organizing conversation context")
            } else text(R.string.injected_vivo_island_thinking, "Thinking")
            is AgentEvent.AssistantBlockStart -> when (event.kind) {
                AgentEvent.AssistantBlockKind.TEXT -> text(R.string.injected_vivo_island_answering, "Writing the answer")
                AgentEvent.AssistantBlockKind.THINKING -> text(R.string.injected_vivo_island_thinking, "Thinking")
                AgentEvent.AssistantBlockKind.TOOL_CALL -> return
            }
            else -> return
        }
        onMain {
            val current = session?.takeIf { it.runId == runId && !it.hidden && !it.terminal } ?: return@onMain
            if (current.detail == detail) return@onMain
            current.detail = detail
            main.removeCallbacks(flush)
            val delay = (UPDATE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPublishedAt)).coerceAtLeast(0L)
            main.postDelayed(flush, delay)
        }
    }

    fun finish(runId: String, state: State) = onMain {
        require(state != State.RUNNING)
        val current = session?.takeIf { it.runId == runId && !it.hidden && !it.terminal } ?: return@onMain
        main.removeCallbacks(flush)
        current.state = state
        current.detail = when (state) {
            State.COMPLETED -> text(R.string.injected_vivo_island_answer_ready, "View the answer in Xiao V or Eta")
            State.FAILED -> text(R.string.injected_failed, "Eta could not complete the task. Try again later")
            State.CANCELLED -> text(R.string.overlay_stopped, "Stopped")
            State.RUNNING -> error("terminal state required")
        }
        publish(current)
        main.removeCallbacks(expire)
        main.postDelayed(expire, current.retentionMs)
    }

    fun clear(runId: String) = onMain {
        session?.takeIf { it.runId == runId }?.let(::hide)
    }

    private fun publish(current: Session) {
        if (current.hidden) return
        try {
            if (!manager.areNotificationsEnabled() || !sceneEnabled(manager)) {
                removeNotification(current)
                return
            }
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Eta", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                })
            }
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                removeNotification(current)
                return
            }
            manager.notify(TAG, NOTIFICATION_ID, notification(current))
            current.published = true
            lastPublishedAt = SystemClock.uptimeMillis()
        } catch (failure: Exception) {
            if (!failureLogged) {
                failureLogged = true
                logger.warn("小 V 原子岛发布不可用，保留 Eta 普通通知: type=${failure.safeLogType()}")
            }
        }
    }

    private fun notification(current: Session): Notification {
        val title = when (current.state) {
            State.RUNNING -> text(R.string.injected_vivo_island_running, "Eta is working")
            State.COMPLETED -> text(R.string.injected_vivo_island_completed, "Eta task completed")
            State.FAILED -> text(R.string.injected_vivo_island_failed, "Eta task failed")
            State.CANCELLED -> text(R.string.injected_vivo_island_cancelled, "Eta task stopped")
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(MainActivity.ACTION_VIEW_EXECUTION)
                .setClassName(ModuleConfig.ETA_PACKAGE, "io.github.mangi.eta.ui.MainActivity")
                .setData(Uri.Builder().scheme("eta-vivo-island").authority("view").appendPath(current.token).build())
                .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, current.conversationKey)
                .putExtra(MainActivity.EXTRA_EXECUTION_SOURCE, VivoHandoff.SOURCE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = action(current, ACTION_STOP)
        val stopLabel = text(R.string.action_stop, "Stop")
        val icon = icon("phone_gpt_execute_left_icon")
        val resultIcon = when (current.state) {
            State.COMPLETED -> icon("phone_gpt_execute_success")
            State.FAILED, State.CANCELLED -> icon("phone_gpt_execute_fail")
            State.RUNNING -> icon
        }
        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", title)
            putCharSequence("notification.superx.baseInfos.content", current.detail)
            putInt("notification.superx.baseInfos.generatingStatus", if (current.terminal) 0 else 1)
            putInt("notification.superx.baseInfos.subInfo", if (current.terminal) 3 else 2)
            if (current.terminal) putParcelable("notification.superx.baseInfos.subImage", resultIcon) else {
                putString("notification.superx.baseInfos.subText", stopLabel)
                putInt("notification.superx.baseInfos.subTextColor", Color.WHITE)
                putInt("notification.superx.baseInfos.subCapsuleBgColor", Color.rgb(62, 85, 190))
                putParcelable("notification.superx.baseInfos.subInfoClickResp", stop)
            }
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putInt("island.superx.rightTemplate", if (current.terminal) 5 else 6)
            putBoolean("island.superx.forceShow", true)
            putBoolean("island.superx.forceShowCard", current.terminal)
            putInt("island.superx.forceShowCardInt", 0)
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", open)
            putInt("island.superx.showTime", if (current.terminal) 30 else -1)
            putString("island.superx.callbackInfos", "eta:${current.token}")
            putInt("island.superx.template", 4)
            putString("island.superx.landingInfo", "{\"mType\":\"app\",\"deepLink\":false,\"mPkgName\":\"com.vivo.ai.copilot\"}")
            putBundle("island.superx.leftInfo", Bundle().apply {
                putParcelable("island.superx.leftInfo.icon", icon)
                putCharSequence("island.superx.leftInfo.content", title)
                if (!current.terminal) putInt("island.superx.leftInfo.generatingStatus", 1)
            })
            putBundle("island.superx.rightInfo", Bundle().apply {
                if (current.terminal) {
                    putCharSequence("island.superx.rightInfo.content", "")
                    putParcelable("island.superx.rightInfo.icon", resultIcon)
                } else {
                    putCharSequence("island.superx.rightInfo.capsuleContent", stopLabel)
                    putInt("island.superx.rightInfo.capsuleBgColor", Color.rgb(62, 85, 190))
                    putParcelable("island.superx.rightInfo.clickResp", stop)
                }
            })
            putBundle("island.superx.baseInfos", base)
        }
        val extras = Bundle().apply {
            putString(EXTRA_TOKEN, current.token)
            putInt("notification.superx.operation", if (current.published) 1 else 0)
            putString("notification.superx.scene", SCENE)
            putInt("notification.superx.template", 4)
            putInt("notification.superx.displays", 0x111)
            putBoolean("notification.superx.islandNotify", false)
            putBoolean("notification.superx.sound", false)
            putBoolean("notification.superx.dismissWhenKill", true)
            putParcelable("notification.superx.clickResp", open)
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.island", island)
            putBundle("notification.superx.capsule", Bundle().apply {
                putInt("notification.superx.capsule.state", 1)
                putParcelable("notification.superx.capsule.icon", icon)
                putString("notification.superx.capsule.content", title)
                putInt("notification.superx.capsule.showTime", if (current.terminal) 30 else -1)
            })
            putBundle("notification.superx.shortInfos", Bundle().apply {
                putParcelable("notification.superx.shortInfos.icon", icon)
                putParcelable("notification.superx.shortInfos.image", resultIcon)
                putParcelable("notification.superx.shortInfos.imageClickResp", open)
                putParcelable("notification.superx.shortInfos.OriginBImage", icon)
                putCharSequence("notification.superx.shortInfos.coreInfoShort", title)
                putCharSequence("notification.superx.shortInfos.describeShort", current.detail)
            })
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon("vivo_push_ard13_notifyicon"))
            .setContentTitle(title).setContentText(current.detail)
            .setContentIntent(open).setDeleteIntent(action(current, ACTION_DISMISS))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setOngoing(!current.terminal).setAutoCancel(false).setExtras(extras)
            .apply {
                if (current.terminal) setTimeoutAfter(current.retentionMs) else {
                    addAction(Notification.Action.Builder(null, stopLabel, stop).build())
                }
            }.build()
    }

    private fun action(current: Session, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(action).setPackage(context.packageName)
            .setData(Uri.Builder().scheme("eta-vivo-island").authority(action).appendPath(current.token).build())
            .putExtra(EXTRA_TOKEN, current.token).putExtra(EXTRA_RUN_ID, current.runId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun hide(current: Session) {
        main.removeCallbacks(flush)
        main.removeCallbacks(expire)
        current.hidden = true
        removeNotification(current)
    }

    private fun removeNotification(current: Session) {
        if (!current.published) return
        // OriginOS rewrites atomic notifications to VIVO_SUPERX_TAG. Match our token before cancelling.
        val posted = runCatching {
            manager.activeNotifications.filter {
                it.id == NOTIFICATION_ID && it.notification.extras.getString(EXTRA_TOKEN) == current.token
            }
        }.getOrDefault(emptyList())
        runCatching {
            val end = notification(current).apply { extras.putInt("notification.superx.operation", 2) }
            manager.notify(TAG, NOTIFICATION_ID, end)
        }
        runCatching { manager.cancel(TAG, NOTIFICATION_ID) }
        posted.filter { it.tag != TAG }.forEach { runCatching { manager.cancel(it.tag, it.id) } }
        current.published = false
    }

    private fun toolDetail(name: String): String {
        val resource = toolDisplayNameResource(name)
        val label = resource?.let { text(it, "Using a tool") }
            ?: text(R.string.injected_vivo_island_tool_generic, "Using a tool")
        return text(R.string.injected_vivo_island_tool, "Working: %s", label)
    }

    private fun icon(name: String): Icon {
        val resource = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (resource != 0) Icon.createWithResource(context, resource)
        else Icon.createWithResource(ModuleConfig.ETA_PACKAGE, R.drawable.ic_notification)
    }

    private fun text(resource: Int, fallback: String, vararg args: Any): String =
        EtaInjectedStrings.get(context, resource, fallback, *args)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private data class Session(
        val runId: String,
        val conversationKey: String,
        var detail: String,
        val token: String = UUID.randomUUID().toString(),
        var state: State = State.RUNNING,
        var published: Boolean = false,
        var hidden: Boolean = false,
    ) {
        val terminal get() = state != State.RUNNING
        val retentionMs get() = if (state == State.CANCELLED) 5_000L else 30_000L
    }

    enum class State { RUNNING, COMPLETED, FAILED, CANCELLED }

    companion object {
        internal const val TAG = "eta:vivo:island"
        internal const val NOTIFICATION_ID = 0x455441
        private const val CHANNEL = "SI_NOTIFICATION_CHANNEL_DEFAULT"
        // FAST_COMMAND is exposed on the supported ROM; GPTAGENT can be unavailable there.
        private const val SCENE = "FAST_COMMAND"
        private const val ACTION_STOP = "io.github.mangi.eta.vivo.action.STOP_ISLAND_TASK"
        private const val ACTION_DISMISS = "io.github.mangi.eta.vivo.action.DISMISS_ISLAND_TASK"
        private const val EXTRA_RUN_ID = "eta_vivo_run_id"
        private const val EXTRA_TOKEN = "eta_vivo_notification_token"
        private const val UPDATE_INTERVAL_MS = 600L

        private fun isSceneEnabled(manager: NotificationManager): Boolean =
            runCatching {
                NotificationManager::class.java.getDeclaredMethod("getSceneStatus", String::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(manager, "com.vivo.ai.copilot", SCENE) == true
            }.getOrDefault(false)
    }
}
