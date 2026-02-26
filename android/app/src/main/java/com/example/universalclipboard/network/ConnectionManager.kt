package com.example.universalclipboard.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.universalclipboard.crypto.IdentityManager
import com.example.universalclipboard.crypto.NoiseHandshake
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection state.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Reconnecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Listener for image transfer progress events.
 */
interface ImageTransferListener {
    fun onProgress(bytesTransferred: Long, bytesTotal: Long)
    fun onComplete()
    fun onFailed(reason: String)
    fun onReceiveStarted(bytesTotal: Long)
    fun onReceiveProgress(bytesReceived: Long, bytesTotal: Long)
    fun onReceiveComplete()
}

/**
 * Manages the connection lifecycle to a clipboard receiver.
 */
class ConnectionManager(
    private val identityManager: IdentityManager,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        internal const val ACK_TIMEOUT_MS = 5_000L
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    var imageTransferListener: ImageTransferListener? = null

    @Volatile
    internal var transport: NoiseTransport? = null
    private var keepaliveJob: Job? = null
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val pendingAck = AtomicReference<CompletableDeferred<Boolean>?>(null)
    internal val pendingImageAck = AtomicReference<CompletableDeferred<Boolean>?>(null)

    // Image receive state
    private var imageReceiveBuffer: ByteArrayOutputStream? = null
    private var imageReceiveTotal: Long = 0
    private var imageReceiveWidth: Int = 0
    private var imageReceiveHeight: Int = 0

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
    fun reconnect(host: String, port: Int, deviceName: String, remotePublicKey: ByteArray, isReconnecting: Boolean = false) {
        scope.launch {
            _state.value = if (isReconnecting) ConnectionState.Reconnecting(deviceName) else ConnectionState.Connecting
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

    /**
     * Send an image to the connected receiver via chunked transfer.
     */
    suspend fun sendImage(
        pngBytes: ByteArray,
        width: Int,
        height: Int,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        val conn = transport ?: return false
        val ack = CompletableDeferred<Boolean>()
        pendingImageAck.set(ack)
        return try {
            val metadata = JSONObject().apply {
                put("width", width)
                put("height", height)
                put("totalBytes", pngBytes.size)
                put("mimeType", "image/png")
            }
            conn.sendMessage(ProtocolMessage.imageSendStart(metadata.toString()))

            var bytesSent = 0L
            val total = pngBytes.size.toLong()
            var offset = 0
            while (offset < pngBytes.size) {
                val end = minOf(offset + MessageType.IMAGE_CHUNK_SIZE, pngBytes.size)
                val chunk = pngBytes.copyOfRange(offset, end)
                conn.sendMessage(ProtocolMessage.imageChunk(chunk))
                bytesSent += chunk.size
                onProgress?.invoke(bytesSent, total)
                offset = end
            }

            conn.sendMessage(ProtocolMessage.imageSendEnd())

            val imageAckTimeout = 10_000L + (pngBytes.size.toLong() / 5_000L)
            withTimeout(imageAckTimeout) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Image ACK timeout", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Image send failed", e)
            handleDisconnect()
            false
        } finally {
            pendingImageAck.compareAndSet(ack, null)
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
                        MessageType.CLIPBOARD_SEND -> {
                            val text = msg.payloadText()
                            Log.i(TAG, "Received clipboard from remote (${text.length} chars)")
                            try {
                                if (context != null) {
                                    withContext(Dispatchers.Main) {
                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Universal Clipboard", text)
                                        clipboardManager.setPrimaryClip(clip)
                                    }
                                }
                                transport?.sendMessage(ProtocolMessage.clipboardAck())
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set clipboard", e)
                                transport?.sendMessage(ProtocolMessage.error("clipboard error: ${e.message}"))
                            }
                        }
                        MessageType.IMAGE_SEND_START -> {
                            try {
                                val json = JSONObject(msg.payloadText())
                                val totalBytes = json.getLong("totalBytes")
                                val width = json.getInt("width")
                                val height = json.getInt("height")

                                if (totalBytes <= 0 || totalBytes > MessageType.MAX_IMAGE_SIZE) {
                                    Log.w(TAG, "Invalid image size: $totalBytes bytes")
                                    transport?.sendMessage(ProtocolMessage.error("invalid image size"))
                                    continue
                                }
                                if (imageReceiveBuffer != null) {
                                    Log.w(TAG, "Concurrent image transfer rejected")
                                    transport?.sendMessage(ProtocolMessage.error("transfer already in progress"))
                                    continue
                                }

                                Log.i(TAG, "Starting image receive: ${width}x${height}, $totalBytes bytes")
                                imageReceiveBuffer = ByteArrayOutputStream(totalBytes.toInt())
                                imageReceiveTotal = totalBytes
                                imageReceiveWidth = width
                                imageReceiveHeight = height
                                imageTransferListener?.onReceiveStarted(totalBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse IMAGE_SEND_START", e)
                                transport?.sendMessage(ProtocolMessage.error("invalid metadata"))
                            }
                        }
                        MessageType.IMAGE_CHUNK -> {
                            val buffer = imageReceiveBuffer
                            if (buffer == null) {
                                Log.w(TAG, "Unexpected IMAGE_CHUNK without active transfer")
                                transport?.sendMessage(ProtocolMessage.error("no active image transfer"))
                            } else if (buffer.size() + msg.payload.size > MessageType.MAX_IMAGE_SIZE) {
                                Log.w(TAG, "Cumulative image data exceeds max size, aborting")
                                imageReceiveBuffer = null
                                transport?.sendMessage(ProtocolMessage.error("image data exceeds max size"))
                                imageTransferListener?.onFailed("cumulative data exceeds max size")
                            } else {
                                buffer.write(msg.payload)
                                imageTransferListener?.onReceiveProgress(
                                    buffer.size().toLong(),
                                    imageReceiveTotal
                                )
                            }
                        }
                        MessageType.IMAGE_SEND_END -> {
                            val buffer = imageReceiveBuffer
                            if (buffer == null) {
                                Log.w(TAG, "Unexpected IMAGE_SEND_END without active transfer")
                                transport?.sendMessage(ProtocolMessage.error("no active image transfer"))
                            } else {
                                val pngBytes = buffer.toByteArray()
                                Log.i(TAG, "Image receive complete: ${imageReceiveWidth}x${imageReceiveHeight}, ${pngBytes.size} bytes")
                                imageReceiveBuffer = null

                                try {
                                    if (context != null) {
                                        // Write image to clipboard via cache file
                                        val cacheFile = java.io.File(context.cacheDir, "received_image.png")
                                        cacheFile.writeBytes(pngBytes)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            cacheFile
                                        )
                                        withContext(Dispatchers.Main) {
                                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newUri(context.contentResolver, "Image", uri)
                                            clipboardManager.setPrimaryClip(clip)
                                        }
                                    }
                                    transport?.sendMessage(ProtocolMessage.imageAck())
                                    imageTransferListener?.onReceiveComplete()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to set clipboard image", e)
                                    transport?.sendMessage(ProtocolMessage.error("clipboard error: ${e.message}"))
                                    imageTransferListener?.onFailed("clipboard error: ${e.message}")
                                }
                            }
                        }
                        MessageType.IMAGE_ACK -> {
                            pendingImageAck.get()?.complete(true)
                        }
                        MessageType.ERROR -> {
                            val text = msg.payloadText()
                            Log.w(TAG, "Remote error: $text")
                            if (imageReceiveBuffer != null) {
                                Log.i(TAG, "Aborting in-progress image receive due to remote error")
                                imageReceiveBuffer = null
                                imageTransferListener?.onFailed("remote error: $text")
                            }
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
        pendingImageAck.getAndSet(null)?.complete(false)
        imageReceiveBuffer = null
        transport?.close()
        transport = null
        _state.value = ConnectionState.Disconnected
    }
}
