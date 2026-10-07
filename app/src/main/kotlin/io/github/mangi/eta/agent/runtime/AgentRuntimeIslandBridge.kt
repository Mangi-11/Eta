package io.github.mangi.eta.agent.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.vivo.VivoHandoff
import io.github.mangi.eta.hook.vivo.VivoIslandNotifications
import io.github.mangi.eta.hook.vivo.VivoIslandWire
import io.github.mangi.eta.hook.vivo.VivoTakeoverPolicy
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID

/** Best-effort native island transport; a missing hook never changes task execution. */
internal class AgentRuntimeIslandBridge(
    context: Context,
    private val logger: AgentLogger,
    private val onStop: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val owner = UUID.randomUUID().toString()
    private val lifetime = Messenger(Handler(Looper.getMainLooper()))
    private var sequence = 0L
    private var current: VivoIslandWire.Snapshot? = null
    private var remote: Messenger? = null
    private var bound = false
    private var unavailable = false
    private var closed = false
    private var failureLogged = false
    private val flush = Runnable { sendCurrent() }
    private val release = Runnable { unbind() }
    private val connectTimeout = Runnable { unavailable("connection timeout") }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed || !bound) return
            main.removeCallbacks(connectTimeout)
            // The unhooked native binder has a different protocol, including a stop-task transaction.
            // Never send a Messenger transaction to that binder.
            if (name != VivoIslandWire.serviceIntent().component || runCatching {
                    service.interfaceDescriptor == "android.os.IMessenger"
                }.getOrDefault(false).not()
            ) {
                unavailable("module bridge unavailable")
                return
            }
            remote = Messenger(service)
            sendCurrent()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            if (!closed && current?.state == VivoIslandNotifications.State.RUNNING) {
                main.removeCallbacks(connectTimeout)
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            }
        }

        override fun onBindingDied(name: ComponentName) = unavailable("binding died")
        override fun onNullBinding(name: ComponentName) = unavailable("null binding")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = current ?: return
            if (closed || active.state != VivoIslandNotifications.State.RUNNING ||
                intent.action != ACTION_STOP || intent.getStringExtra(EXTRA_TOKEN) != active.token ||
                intent.getStringExtra(EXTRA_RUN) != active.runId
            ) return
            onStop(active.runId)
        }
    }

    init {
        this.context.registerReceiver(receiver, IntentFilter(ACTION_STOP).apply {
            addDataScheme("eta-runtime-island")
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    fun start(request: AgentRuntimeWire.RunRequest) = onMain {
        if (closed) return@onMain
        clearCurrent()
        // VivoHooks already owns the early/native takeover lifecycle and its island.
        if (request.handoff?.source == VivoHandoff.SOURCE || !supportsCopilot()) {
            unbind()
            return@onMain
        }
        unavailable = false
        main.removeCallbacks(release)
        val token = UUID.randomUUID().toString()
        val source = request.handoff?.source ?: AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
        val key = when (source) {
            AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE -> request.handoff?.payload?.let {
                AgentUiHandoffPayload.from(it).conversationId
            }.orEmpty()
            else -> request.handoff?.payload?.let { AgentExternalArchivePayload.from(it)?.conversationKey }.orEmpty()
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_VIEW_EXECUTION)
                .setData(uri("view", token))
                .putExtra(MainActivity.EXTRA_EXECUTION_SOURCE, source)
                .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_STOP).setPackage(ModuleConfig.ETA_PACKAGE).setData(uri("stop", token))
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_RUN, request.runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        current = VivoIslandWire.Snapshot(
            owner, ++sequence, token, request.runId, VivoIslandNotifications.State.RUNNING,
            VivoIslandWire.Progress(VivoIslandWire.Phase.PREPARING), open, stop,
        )
        sendCurrent()
    }

    fun update(runId: String, event: AgentEvent, virtualScreen: Boolean = false) {
        val progress = VivoIslandWire.Progress.from(event, virtualScreen) ?: return
        onMain {
            val active = current?.takeIf {
                !closed && it.runId == runId && it.state == VivoIslandNotifications.State.RUNNING
            } ?: return@onMain
            if (active.progress == progress) return@onMain
            current = active.copy(sequence = ++sequence, progress = progress)
            if (!main.hasCallbacks(flush)) main.postDelayed(flush, UPDATE_INTERVAL_MS)
        }
    }

    fun finish(result: AgentRuntimeWire.RunResult) = onMain {
        val active = current?.takeIf {
            !closed && it.runId == result.runId && it.state == VivoIslandNotifications.State.RUNNING
        } ?: return@onMain
        main.removeCallbacks(flush)
        val state = when {
            result.ok -> VivoIslandNotifications.State.COMPLETED
            result.error == "已停止" -> VivoIslandNotifications.State.CANCELLED
            else -> VivoIslandNotifications.State.FAILED
        }
        current = active.copy(sequence = ++sequence, state = state)
        sendCurrent()
        main.removeCallbacks(release)
        main.postDelayed(release, if (state == VivoIslandNotifications.State.CANCELLED) 5_000L else 30_000L)
    }

    fun close() = onMain {
        if (closed) return@onMain
        if (current?.state == VivoIslandNotifications.State.RUNNING) clearCurrent()
        closed = true
        main.removeCallbacksAndMessages(null)
        unbind()
        context.unregisterReceiver(receiver)
    }

    private fun clearCurrent() {
        main.removeCallbacks(flush)
        current?.let { send(it.copy(sequence = ++sequence, clear = true)) }
        current = null
    }

    private fun sendCurrent() {
        val active = current?.takeUnless { closed || unavailable } ?: return
        if (remote != null) send(active) else if (!bound) {
            try {
                bound = context.bindService(VivoIslandWire.serviceIntent(), connection, Context.BIND_AUTO_CREATE)
                if (bound) main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS) else unavailable("bind rejected")
            } catch (failure: Exception) {
                unavailable(failure.safeLogType())
            }
        }
    }

    private fun send(snapshot: VivoIslandWire.Snapshot) {
        val target = remote ?: return
        try {
            target.send(Message.obtain(null, VivoIslandWire.MSG_RENDER).apply {
                data = snapshot.toBundle()
                replyTo = lifetime
            })
        } catch (failure: Exception) {
            unavailable(failure.safeLogType())
        }
    }

    private fun unavailable(reason: String) {
        unavailable = true
        if (!failureLogged) {
            failureLogged = true
            logger.warn("小 V 原子岛桥接不可用，保留 Eta 普通通知: $reason")
        }
        unbind()
    }

    private fun unbind() {
        main.removeCallbacks(connectTimeout)
        remote = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun supportsCopilot(): Boolean = runCatching {
        VivoTakeoverPolicy.isSupportedVersion(context.packageManager.getPackageInfo(
            ModuleConfig.VIVO_COPILOT_PACKAGE, PackageManager.PackageInfoFlags.of(0),
        ).longVersionCode)
    }.getOrDefault(false)

    private fun uri(action: String, token: String): Uri = Uri.Builder().scheme("eta-runtime-island")
        .authority(action).appendPath(owner).appendPath(token).build()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    companion object {
        private const val ACTION_STOP = "io.github.mangi.eta.vivo.action.STOP_RUNTIME_ISLAND"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_RUN = "run"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val UPDATE_INTERVAL_MS = 600L
    }
}
