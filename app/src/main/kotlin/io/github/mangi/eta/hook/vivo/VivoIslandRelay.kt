package io.github.mangi.eta.hook.vivo

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.core.ModuleConfig

/** Runs inside the hooked Copilot service. The Binder sender UID is checked before decoding data. */
internal class VivoIslandRelay(
    context: Context,
    private val notifications: VivoIslandNotifications,
    private val localTaskActive: () -> Boolean,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var owner: String? = null
    private var sequence = 0L
    private var current: VivoIslandWire.Snapshot? = null
    private var client: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val retiredOwners = linkedSetOf<String>()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) = receive(msg)
    })
    val binder: IBinder get() = messenger.binder

    internal fun receive(msg: Message) {
        if (msg.what != VivoIslandWire.MSG_RENDER || !isEtaUid(msg.sendingUid)) return
        val snapshot = VivoIslandWire.fromBundle(msg.data) ?: return
        val reply = msg.replyTo?.binder ?: return
        if (snapshot.owner in retiredOwners) return
        if (owner != snapshot.owner) {
            owner?.let {
                retiredOwners += it
                if (retiredOwners.size > 8) retiredOwners.remove(retiredOwners.first())
            }
            current?.let { notifications.clear(it.runId) }
            detachClient()
            owner = snapshot.owner
            sequence = 0L
            current = null
        }
        if (snapshot.sequence <= sequence) return
        sequence = snapshot.sequence
        if (snapshot.clear) {
            current?.takeIf { it.token == snapshot.token }?.let {
                notifications.clear(it.runId)
                current = null
                detachClient()
            }
            return
        }
        if (current?.token != snapshot.token) {
            if (localTaskActive()) return
            detachClient()
            notifications.start(snapshot.runId, "", snapshot.open, snapshot.stop)
        } else if (current?.state != VivoIslandNotifications.State.RUNNING) {
            return
        }
        current = snapshot
        notifications.update(snapshot.runId, snapshot.progress)
        if (snapshot.state != VivoIslandNotifications.State.RUNNING) {
            notifications.finish(snapshot.runId, snapshot.state)
            detachClient()
        } else if (client != reply) {
            detachClient()
            val token = snapshot.token
            val recipient = IBinder.DeathRecipient {
                main.post {
                    current?.takeIf { it.token == token && it.state == VivoIslandNotifications.State.RUNNING }?.let {
                        notifications.clear(it.runId)
                        current = null
                        detachClient()
                    }
                }
            }
            try {
                reply.linkToDeath(recipient, 0)
                client = reply
                death = recipient
            } catch (_: Exception) {
                notifications.clear(snapshot.runId)
                current = null
            }
        }
    }

    private fun detachClient() {
        death?.let { recipient -> runCatching { client?.unlinkToDeath(recipient, 0) } }
        client = null
        death = null
    }

    private fun isEtaUid(uid: Int): Boolean = uid >= 0 && runCatching {
        context.packageManager.getPackageUid(ModuleConfig.ETA_PACKAGE, 0) == uid
    }.getOrDefault(false)
}
