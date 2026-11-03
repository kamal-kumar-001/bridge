package com.bridge.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bridge.app.R
import com.bridge.app.core.model.ConnectionStatus
import com.bridge.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var controller: ServiceController

    companion object {
        const val CHANNEL_ID = "bridge_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bridge.app.action.START"
        const val ACTION_STOP = "com.bridge.app.action.STOP"
        const val ACTION_SYNC_CLIPBOARD = "com.bridge.app.action.SYNC_CLIPBOARD"
        const val ACTION_DISCONNECT = "com.bridge.app.action.DISCONNECT"

        fun startService(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        controller = ServiceController.getInstance(this)
        controller.start()

        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_CLIPBOARD -> {
                controller.clipboardManager.syncCurrentClipboardNow()
            }
            ACTION_DISCONNECT -> {
                controller.disconnect()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(null, ConnectionStatus.DISCONNECTED))
            }
        }
        return START_STICKY
    }

    private fun observeConnectionState() {
        scope.launch {
            controller.connectionStatus.collect { status ->
                val device = controller.connectedDevice.value
                val notification = buildNotification(device?.name, status)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(deviceName: String?, status: ConnectionStatus): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val syncClipIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_SYNC_CLIPBOARD
        }
        val pendingSyncClip = PendingIntent.getService(
            this, 1, syncClipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pendingDisconnect = PendingIntent.getService(
            this, 2, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when (status) {
            ConnectionStatus.CONNECTED -> getString(R.string.service_notification_connected, deviceName ?: "Desktop")
            ConnectionStatus.CONNECTING -> "Connecting..."
            ConnectionStatus.PAIRING -> "Pairing..."
            ConnectionStatus.DISCOVERING -> "Discovering nearby devices..."
            ConnectionStatus.DISCONNECTED -> getString(R.string.service_notification_waiting)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (status == ConnectionStatus.CONNECTED) {
            builder.addAction(
                android.R.drawable.ic_menu_send,
                getString(R.string.action_sync_clipboard),
                pendingSyncClip
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                pendingDisconnect
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bridge Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bridge connection active with your computer"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
