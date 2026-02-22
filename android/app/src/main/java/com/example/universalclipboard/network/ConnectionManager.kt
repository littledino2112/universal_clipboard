package com.example.universalclipboard.network

import android.util.Log
import com.example.universalclipboard.crypto.IdentityManager
import com.example.universalclipboard.crypto.NoiseHandshake
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection state.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Manages the connection lifecycle to a clipboard receiver.
 */
class ConnectionManager(
    private val identityManager: IdentityManager
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        internal const val ACK_TIMEOUT_MS = 5_000L
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    @Volatile
    internal var transport: NoiseTransport? = null
    private var keepaliveJob: Job? = null
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val pendingAck = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /**
     * Connect to a receiver with a pairing code (first time).
     */
    fun connectWithPairing(host: String, port: Int, pairingCode: String) {
        scope.launch {
            _state.value = ConnectionState.Connecting
            try {
                val keyPair = identityManager.getOrCreateKeyPair()
                val socket = Socket(host, port)
                socket.tcpNoDelay = true

                val (cipherPair, remoteKey) = NoiseHandshake.pairingHandshake(
                    socket, keyPair, pairingCode
                )

                val deviceName = "receiver-${remoteKey.take(4).joinToString("") { "%02x".format(it) }}"
                identityManager.savePairedDevice(deviceName, remoteKey, host, port)

                val conn = NoiseTransport(socket, cipherPair)
                transport = conn

                // Exchange device info
                conn.sendMessage(ProtocolMessage.deviceInfo("Android"))
                val infoMsg = conn.recvMessage()
                val remoteName = if (infoMsg.type == MessageType.DEVICE_INFO) {
                    try {
                        org.json.JSONObject(infoMsg.payloadText()).optString("name", deviceName)
                    } catch (_: Exception) { deviceName }
                } else deviceName

                // Update stored name with host/port
                identityManager.savePairedDevice(remoteName, remoteKey, host, port)

                _state.value = ConnectionState.Connected(remoteName)
                startKeepalive()
                startReceiving()

                Log.i(TAG, "Paired and connected to $remoteName")
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                _state.value = ConnectionState.Error("Pairing failed: ${e.message}")
            }
        }
    }

    /**
     * Reconnect to a previously paired device.
     */
    fun reconnect(host: String, port: Int, deviceName: String, remotePublicKey: ByteArray) {
        scope.launch {
            _state.value = ConnectionState.Connecting
            try {
                val keyPair = identityManager.getOrCreateKeyPair()
                val socket = Socket(host, port)
                socket.tcpNoDelay = true

                val cipherPair = NoiseHandshake.pairedHandshake(
                    socket, keyPair, remotePublicKey
                )

                val conn = NoiseTransport(socket, cipherPair)
                transport = conn

                // Exchange device info
                conn.sendMessage(ProtocolMessage.deviceInfo("Android"))
                val infoMsg = conn.recvMessage()

                // Keep address fresh
                identityManager.savePairedDevice(deviceName, remotePublicKey, host, port)

                _state.value = ConnectionState.Connected(deviceName)
                startKeepalive()
                startReceiving()

                Log.i(TAG, "Reconnected to $deviceName")
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection failed", e)
                _state.value = ConnectionState.Error("Reconnection failed: ${e.message}")
            }
        }
    }

    /**
     * Send clipboard text to the connected receiver.
     * The receiver loop handles all incoming messages; this method waits
     * on a [CompletableDeferred] that the receiver loop completes when
     * it sees a [MessageType.CLIPBOARD_ACK].
     */
    suspend fun sendClipboardText(text: String): Boolean {
        val conn = transport ?: return false
        val ack = CompletableDeferred<Boolean>()
        pendingAck.set(ack)
        return try {
            conn.sendMessage(ProtocolMessage.clipboardSend(text))
            withTimeout(ACK_TIMEOUT_MS) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "ACK timeout", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            handleDisconnect()
            false
        } finally {
            pendingAck.compareAndSet(ack, null)
        }
    }

    fun disconnect() {
        keepaliveJob?.cancel()
        receiveJob?.cancel()
        transport?.close()
        transport = null
        _state.value = ConnectionState.Disconnected
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    transport?.sendMessage(ProtocolMessage.ping())
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive failed", e)
                    handleDisconnect()
                    break
                }
            }
        }
    }

    internal fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            while (isActive) {
                try {
                    val msg = transport?.recvMessage() ?: break
                    when (msg.type) {
                        MessageType.CLIPBOARD_ACK -> {
                            pendingAck.get()?.complete(true)
                        }
                        MessageType.PING -> {
                            transport?.sendMessage(ProtocolMessage.pong())
                        }
                        MessageType.PONG -> { /* keepalive response */ }
                        MessageType.ERROR -> {
                            Log.w(TAG, "Remote error: ${msg.payloadText()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Receive error", e)
                    handleDisconnect()
                    break
                }
            }
        }
    }

    private fun handleDisconnect() {
        keepaliveJob?.cancel()
        receiveJob?.cancel()
        pendingAck.getAndSet(null)?.complete(false)
        transport?.close()
        transport = null
        _state.value = ConnectionState.Disconnected
    }
}
