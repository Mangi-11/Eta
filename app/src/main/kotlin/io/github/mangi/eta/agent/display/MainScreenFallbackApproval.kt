package io.github.mangi.eta.agent.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class MainScreenFallbackDecision { PENDING, ALLOWED, DENIED, CANCELLED, DISABLED, TIMEOUT, UNAVAILABLE }

/** An expired or cancelled notification cannot authorize a later run. */
internal class MainScreenFallbackRequest(
    val owner: String,
    timeoutMs: Long = 180_000,
) {
    val token: String = UUID.randomUUID().toString()
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val decision = AtomicReference(MainScreenFallbackDecision.PENDING)
    private val ready = CountDownLatch(1)

    fun resolve(value: MainScreenFallbackDecision): Boolean {
        require(value != MainScreenFallbackDecision.PENDING)
        val actual =
            if (System.nanoTime() >= deadline) MainScreenFallbackDecision.TIMEOUT else value
        val changed = decision.compareAndSet(MainScreenFallbackDecision.PENDING, actual)
        if (changed) ready.countDown()
        return changed && actual == value
    }

    fun await(isCancelled: () -> Boolean, stillAllowed: () -> Boolean): MainScreenFallbackDecision {
        try {
            while (true) {
                if (isCancelled()) {
                    resolve(MainScreenFallbackDecision.CANCELLED); return MainScreenFallbackDecision.CANCELLED
                }
                if (!stillAllowed()) {
                    resolve(MainScreenFallbackDecision.DISABLED); return MainScreenFallbackDecision.DISABLED
                }
                if (System.nanoTime() >= deadline) {
                    resolve(MainScreenFallbackDecision.TIMEOUT); return MainScreenFallbackDecision.TIMEOUT
                }
                decision.get().takeIf { it != MainScreenFallbackDecision.PENDING }
                    ?.let { return it }
                ready.await(100, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            resolve(MainScreenFallbackDecision.CANCELLED)
            Thread.currentThread().interrupt()
            return MainScreenFallbackDecision.CANCELLED
        }
    }
}

internal object MainScreenFallbackApproval {
    private const val CHANNEL = "eta_ui_fallback"
    private const val NOTIFICATION_ID = 1111
    internal const val ACTION_ALLOW = "io.github.mangi.eta.action.ALLOW_MAIN_SCREEN_FALLBACK"
    internal const val ACTION_DENY = "io.github.mangi.eta.action.DENY_MAIN_SCREEN_FALLBACK"
    private const val TOKEN = "fallback_request"

    private data class Pending(
        val request: MainScreenFallbackRequest,
        val manager: NotificationManager
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun request(
        context: Context,
        owner: String,
        tool: String,
        reason: String,
        isCancelled: () -> Boolean,
        stillAllowed: () -> Boolean,
    ): MainScreenFallbackDecision {
        if (owner.isBlank() || Looper.myLooper() == Looper.getMainLooper()) return MainScreenFallbackDecision.UNAVAILABLE
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()
        ) return MainScreenFallbackDecision.UNAVAILABLE
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                app.getString(R.string.virtual_screen_fallback_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return MainScreenFallbackDecision.UNAVAILABLE
        }
        val ticket = MainScreenFallbackRequest(owner)
        pending[ticket.token] = Pending(ticket, manager)
        try {
            val reasonText = app.getString(
                when (reason) {
                    "APP_ALREADY_RUNNING", "DISPLAY_APP_UNSUPPORTED", "DISPLAY_LAUNCH_REJECTED", "DISPLAY_LAUNCH_MISMATCH" -> R.string.virtual_screen_fallback_reason_app
                    "ROOT_REQUIRED", "ROOT_DISPLAY_UNAVAILABLE", "DEVICE_UNSUPPORTED" -> R.string.virtual_screen_fallback_reason_device
                    else -> R.string.virtual_screen_fallback_reason_action
                }
            )
            val open = PendingIntent.getActivity(
                app, NOTIFICATION_ID, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val label = toolDisplayNameResource(tool)?.let { app.getString(it) } ?: app.getString(R.string.virtual_screen_viewer_title)
            val text =
                app.getString(R.string.virtual_screen_fallback_notification_body, label, reasonText)
            val notification = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.virtual_screen_fallback_notification_title))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_ERROR).setTimeoutAfter(180_000)
                .setDeleteIntent(action(app, ticket.token, ACTION_DENY))
                .addAction(
                    Notification.Action.Builder(
                        null, app.getString(R.string.virtual_screen_fallback_allow),
                        action(app, ticket.token, ACTION_ALLOW)
                    ).setAuthenticationRequired(true).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null, app.getString(R.string.virtual_screen_fallback_deny),
                        action(app, ticket.token, ACTION_DENY)
                    ).build()
                )
                .build()
            manager.notify(ticket.token, NOTIFICATION_ID, notification)
            return ticket.await(isCancelled, stillAllowed)
        } catch (_: RuntimeException) {
            return MainScreenFallbackDecision.UNAVAILABLE
        } finally {
            pending.remove(ticket.token)
            ticket.resolve(MainScreenFallbackDecision.CANCELLED)
            manager.cancel(ticket.token, NOTIFICATION_ID)
        }
    }

    private fun action(context: Context, token: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MainScreenFallbackReceiver::class.java).setAction(action)
                .setData(Uri.parse("eta-ui-fallback://request/$token/$action"))
                .putExtra(TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun receive(intent: Intent) {
        val decision = when (intent.action) {
            ACTION_ALLOW -> MainScreenFallbackDecision.ALLOWED
            ACTION_DENY -> MainScreenFallbackDecision.DENIED
            else -> return
        }
        val token = intent.getStringExtra(TOKEN) ?: return
        val item = pending[token] ?: return
        if (item.request.resolve(decision)) item.manager.cancel(token, NOTIFICATION_ID)
    }

    fun cancelOwner(owner: String) =
        pending.values.filter { it.request.owner == owner }.forEach(::cancel)

    fun cancelAll() = pending.values.forEach(::cancel)
    private fun cancel(item: Pending) {
        item.request.resolve(MainScreenFallbackDecision.CANCELLED)
        item.manager.cancel(item.request.token, NOTIFICATION_ID)
    }
}

internal class MainScreenFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        MainScreenFallbackApproval.receive(intent)
}
