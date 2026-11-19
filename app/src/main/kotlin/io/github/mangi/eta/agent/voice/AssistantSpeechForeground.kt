package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings

/** Overlay 不等同于前台 Activity；只在可见浮窗采集或播放音频期间提升服务类型。 */
internal class AssistantSpeechForeground(private val service: Service) {
    fun recording() = start(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        AppStrings.get(R.string.voice_i18n_fg_listening, "Eta 正在聆听"),
    )
    fun playback() = start(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        AppStrings.get(R.string.voice_i18n_fg_speaking, "Eta 正在朗读"),
    )

    private fun start(type: Int, title: String) {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                AppStrings.get(R.string.voice_i18n_fg_channel_name, "助手语音"),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop = PendingIntent.getService(service, 0,
            Intent(service, EtaAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4285F4.toInt())
            .setContentTitle(title)
            .setContentText(AppStrings.get(R.string.voice_i18n_fg_content_text, "仅在助手浮窗可见时运行"))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    AppStrings.get(R.string.voice_i18n_fg_action_stop, "停止"),
                    stop,
                ).build(),
            )
            .build()
        try {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } catch (_: RuntimeException) {
            throw SpeechFailure(
                SpeechErrorCode.PERMISSION,
                AppStrings.get(
                    R.string.voice_i18n_fg_permission_denied,
                    "系统不允许当前浮窗使用音频，请检查麦克风权限和默认数字助理设置",
                ),
            )
        }
    }

    fun stop() { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.STOP_ASSISTANT_SPEECH"
        private const val CHANNEL = "eta_assistant_speech"
        private const val NOTIFICATION_ID = 4203
    }
}
