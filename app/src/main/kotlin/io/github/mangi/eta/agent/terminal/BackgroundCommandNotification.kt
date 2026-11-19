package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

internal object BackgroundCommandNotification {
    private const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    fun show(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.execution_bg_cmd_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (task.commandStatus) {
            "completed" -> context.getString(R.string.execution_bg_cmd_completed)
            "timed_out" -> context.getString(R.string.execution_bg_cmd_timed_out)
            "stopped" -> context.getString(R.string.execution_bg_cmd_stopped)
            "interrupted" -> context.getString(R.string.execution_bg_cmd_interrupted)
            else -> context.getString(R.string.execution_bg_cmd_failed)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4285F4.toInt())
            .setContentTitle(title)
            .setContentText(task.description.ifBlank { context.getString(R.string.execution_bg_cmd_open_hint) })
            .setSubText(task.exitCode?.let { context.getString(R.string.execution_bg_cmd_exit_code, it) })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }
}
