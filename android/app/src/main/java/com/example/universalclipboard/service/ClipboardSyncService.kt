package com.example.universalclipboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.universalclipboard.MainActivity
import com.example.universalclipboard.crypto.IdentityManager
import com.example.universalclipboard.crypto.PairedDevice
import com.example.universalclipboard.network.ConnectionManager
import com.example.universalclipboard.network.ConnectionState
import com.example.universalclipboard.network.DeviceDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ClipboardSyncService : Service() {

    companion object {
        private const val TAG = "ClipboardSyncService"
        private const val CHANNEL_ID = "clipboard_sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.example.universalclipboard.DISCONNECT"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardSyncService = this@ClipboardSyncService
    }

    private val binder = LocalBinder()
    private lateinit var identityManager: IdentityManager
    lateinit var connectionManager: ConnectionManager
        private set
    lateinit var discovery: DeviceDiscovery
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var lastConnectedDevice: PairedDevice? = null
    private var autoReconnectEnabled = false

    override fun onCreate() {
        super.onCreate()
        identityManager = IdentityManager(this)
        connectionManager = ConnectionManager(identityManager)
        discovery = DeviceDiscovery(this)
        createNotificationChannel()
        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            userDisconnect()
            return START_NOT_STICKY
        }
        startForegroundWithNotification("Clipboard sync ready")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        connectionManager.destroy()
        discovery.stopDiscovery()
        serviceScope.cancel()
    }

    fun connectWithPairing(host: String, port: Int, pairingCode: String) {
        autoReconnectEnabled = true
        connectionManager.connectWithPairing(host, port, pairingCode)
    }

    fun reconnectToDevice(device: PairedDevice) {
        if (device.host == null || device.port == null) return
        autoReconnectEnabled = true
        lastConnectedDevice = device
        connectionManager.reconnect(device.host, device.port, device.name, device.publicKey)
    }

    fun userDisconnect() {
        autoReconnectEnabled = false
        lastConnectedDevice = null
        reconnectJob?.cancel()
        connectionManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            connectionManager.state.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        reconnectJob?.cancel()
                        // Update last connected device from paired devices list
                        val paired = identityManager.getPairedDevices()
                            .find { it.name == state.deviceName }
                        if (paired != null) {
                            lastConnectedDevice = paired
                        }
                        updateNotification("Connected to ${state.deviceName}")
                    }
                    is ConnectionState.Connecting -> {
                        updateNotification("Connecting...")
                    }
                    is ConnectionState.Error -> {
                        updateNotification("Error: ${state.message}")
                        scheduleReconnect()
                    }
                    ConnectionState.Disconnected -> {
                        updateNotification("Disconnected")
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled) return
        val device = lastConnectedDevice ?: return
        if (device.host == null || device.port == null) return

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive && autoReconnectEnabled) {
                delay(backoff)
                val currentState = connectionManager.state.value
                if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
                    break
                }
                Log.i(TAG, "Auto-reconnecting to ${device.name} (backoff=${backoff}ms)")
                updateNotification("Reconnecting to ${device.name}...")
                connectionManager.reconnect(device.host, device.port, device.name, device.publicKey)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                // Wait for the connection attempt to resolve
                delay(5_000L)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Clipboard Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows clipboard sync connection status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Universal Clipboard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(tapPending)
            .addAction(
                Notification.Action.Builder(
                    null, "Disconnect", disconnectPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
