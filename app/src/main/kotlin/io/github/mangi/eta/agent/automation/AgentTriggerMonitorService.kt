package io.github.mangi.eta.agent.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/** 仅在用户启用了事件任务时监控系统事件；退出后不补发历史事件。 */
internal class AgentTriggerMonitorService : Service() {
    private var networkRegistered = false
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> "device_unlocked"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_POWER_CONNECTED -> "charging_connected"
                Intent.ACTION_POWER_DISCONNECTED -> "charging_disconnected"
                Intent.ACTION_BATTERY_LOW -> "battery_low"
                Intent.ACTION_BATTERY_OKAY -> "battery_okay"
                Intent.ACTION_HEADSET_PLUG -> if (intent.getIntExtra(
                        "state",
                        0
                    ) == 1
                ) "headset_connected" else "headset_disconnected"

                BluetoothDevice.ACTION_ACL_CONNECTED -> "bluetooth_connected"
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> "bluetooth_disconnected"
                else -> return
            }
            AgentTaskScheduler.publish(context, type, System.currentTimeMillis().toString())
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private val types = mutableMapOf<Network, String>()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            if (types.put(
                    network,
                    transport
                ) != transport
            ) AgentTaskScheduler.publish(
                this@AgentTriggerMonitorService,
                "network_connected",
                "$network:$transport",
                org.json.JSONObject().put("transport", transport)
            )
        }

        override fun onLost(network: Network) {
            val transport = types.remove(network) ?: "other"
            AgentTaskScheduler.publish(
                this@AgentTriggerMonitorService,
                "network_disconnected",
                network.toString(),
                org.json.JSONObject().put("transport", transport)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.automation_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val open = PendingIntent.getActivity(
            this,
            1111,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.automation_monitor_title))
                .setContentText(getString(R.string.automation_monitor_summary))
                .setContentIntent(open).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(
            1111,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        else startForeground(1111, notification)
        val filter = IntentFilter().apply {
            listOf(
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_HEADSET_PLUG,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED
            ).forEach(::addAction)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            networkCallback
        )
        networkRegistered = true
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        isRunning = false
        if (receiverRegistered) unregisterReceiver(receiver)
        if (networkRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(
            networkCallback
        )
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "eta_automation_triggers"
        @Volatile
        var isRunning = false
            private set
    }
}
