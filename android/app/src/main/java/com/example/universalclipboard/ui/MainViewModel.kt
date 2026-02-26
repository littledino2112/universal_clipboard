package com.example.universalclipboard.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.universalclipboard.crypto.IdentityManager
import com.example.universalclipboard.crypto.PairedDevice
import com.example.universalclipboard.data.ClipboardItem
import com.example.universalclipboard.network.*
import com.example.universalclipboard.service.ClipboardSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Transfer state for image send/receive progress.
 */
sealed class ImageTransferState {
    data object Idle : ImageTransferState()
    data object Preparing : ImageTransferState()
    data class Sending(val bytesSent: Long, val bytesTotal: Long) : ImageTransferState()
    data class Receiving(val bytesReceived: Long, val bytesTotal: Long) : ImageTransferState()
    data class Failed(val reason: String) : ImageTransferState()
}

/**
 * Screen state for the main clipboard UI.
 */
data class MainUiState(
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val pairedDevices: List<PairedDevice> = emptyList(),
    val pairingCode: String = "",
    val manualIp: String = "",
    val manualPort: String = "9876",
    val snackbarMessage: String? = null,
    val imageTransferState: ImageTransferState = ImageTransferState.Idle
) {
    val isSendEnabled: Boolean
        get() = imageTransferState is ImageTransferState.Idle
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_CLIPBOARD_ITEMS = 10
    }

    private val identityManager = IdentityManager(application)

    private var service: ClipboardSyncService? = null
    private var connectionStateJob: Job? = null
    private var discoveryJob: Job? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ClipboardSyncService.LocalBinder
            service = localBinder.getService()
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionStateJob?.cancel()
            discoveryJob?.cancel()
            service = null
        }
    }

    init {
        // Load paired devices
        refreshPairedDevices()
        // Bind to service
        bindService()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, ClipboardSyncService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        val svc = service ?: return

        connectionStateJob?.cancel()
        connectionStateJob = viewModelScope.launch {
            svc.connectionManager.state.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                if (state is ConnectionState.Connected) {
                    refreshPairedDevices()
                }
            }
        }

        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            svc.discovery.devices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }
    }

    private fun ensureServiceStarted() {
        val context = getApplication<Application>()
        val intent = Intent(context, ClipboardSyncService::class.java)
        context.startForegroundService(intent)
        if (service == null) {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun addClipboardItem(text: String) {
        if (text.isBlank()) return
        _uiState.update { state ->
            val items = state.clipboardItems.toMutableList()
            items.add(0, ClipboardItem.TextItem(text = text))
            if (items.size > MAX_CLIPBOARD_ITEMS) {
                items.removeAt(items.lastIndex)
            }
            state.copy(clipboardItems = items)
        }
    }

    fun addImageItem(pngBytes: ByteArray, width: Int, height: Int) {
        _uiState.update { state ->
            val items = state.clipboardItems.toMutableList()
            items.add(
                0,
                ClipboardItem.ImageItem(
                    pngBytes = pngBytes,
                    width = width,
                    height = height,
                    sizeBytes = pngBytes.size.toLong()
                )
            )
            if (items.size > MAX_CLIPBOARD_ITEMS) {
                items.removeAt(items.lastIndex)
            }
            state.copy(clipboardItems = items)
        }
    }

    fun removeClipboardItem(id: Long) {
        _uiState.update { state ->
            state.copy(clipboardItems = state.clipboardItems.filter { it.id != id })
        }
    }

    fun sendClipboardItem(id: Long) {
        val item = _uiState.value.clipboardItems.find { it.id == id } ?: return
        when (item) {
            is ClipboardItem.TextItem -> sendTextItem(id, item)
            is ClipboardItem.ImageItem -> sendImageItem(id, item)
        }
    }

    private fun sendTextItem(id: Long, item: ClipboardItem.TextItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = service?.connectionManager?.sendClipboardText(item.text) ?: false
            if (success) {
                _uiState.update { state ->
                    state.copy(
                        clipboardItems = state.clipboardItems.map {
                            if (it.id == id) it.withSent(true) else it
                        },
                        snackbarMessage = "Sent to clipboard!"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(snackbarMessage = "Failed to send. Check connection.")
                }
            }
        }
    }

    private fun sendImageItem(id: Long, item: ClipboardItem.ImageItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(imageTransferState = ImageTransferState.Preparing) }
            try {
                _uiState.update {
                    it.copy(
                        imageTransferState = ImageTransferState.Sending(
                            0,
                            item.pngBytes.size.toLong()
                        )
                    )
                }
                val success = service?.connectionManager?.sendImage(
                    item.pngBytes,
                    item.width,
                    item.height
                ) { bytesSent, bytesTotal ->
                    _uiState.update {
                        it.copy(
                            imageTransferState = ImageTransferState.Sending(
                                bytesSent,
                                bytesTotal
                            )
                        )
                    }
                } ?: false

                if (success) {
                    _uiState.update { state ->
                        state.copy(
                            clipboardItems = state.clipboardItems.map {
                                if (it.id == id) it.withSent(true) else it
                            },
                            imageTransferState = ImageTransferState.Idle,
                            snackbarMessage = "Image sent!"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            imageTransferState = ImageTransferState.Failed("Send failed"),
                            snackbarMessage = "Failed to send image."
                        )
                    }
                    delay(3000)
                    _uiState.update { it.copy(imageTransferState = ImageTransferState.Idle) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        imageTransferState = ImageTransferState.Failed(
                            e.message ?: "Unknown error"
                        ),
                        snackbarMessage = "Image transfer failed."
                    )
                }
                delay(3000)
                _uiState.update { it.copy(imageTransferState = ImageTransferState.Idle) }
            }
        }
    }

    fun onImageReceiveStarted(bytesTotal: Long) {
        _uiState.update {
            it.copy(imageTransferState = ImageTransferState.Receiving(0, bytesTotal))
        }
    }

    fun onImageReceiveProgress(bytesReceived: Long, bytesTotal: Long) {
        _uiState.update {
            it.copy(imageTransferState = ImageTransferState.Receiving(bytesReceived, bytesTotal))
        }
    }

    fun onImageReceiveComplete() {
        _uiState.update {
            it.copy(
                imageTransferState = ImageTransferState.Idle,
                snackbarMessage = "Image received!"
            )
        }
    }

    fun onImageTransferFailed(reason: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(imageTransferState = ImageTransferState.Failed(reason))
            }
            delay(3000)
            _uiState.update { it.copy(imageTransferState = ImageTransferState.Idle) }
        }
    }

    fun updatePairingCode(code: String) {
        if (code.length <= 6 && code.all { it.isDigit() }) {
            _uiState.update { it.copy(pairingCode = code) }
        }
    }

    fun updateManualIp(ip: String) {
        _uiState.update { it.copy(manualIp = ip) }
    }

    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    fun pairWithDevice(device: DiscoveredDevice) {
        val code = _uiState.value.pairingCode
        if (code.length != 6) {
            _uiState.update { it.copy(snackbarMessage = "Enter the 6-digit code from the receiver") }
            return
        }
        ensureServiceStarted()
        service?.connectWithPairing(device.host, device.port, code)
    }

    fun pairWithManualAddress() {
        val code = _uiState.value.pairingCode
        val ip = _uiState.value.manualIp
        val port = _uiState.value.manualPort.toIntOrNull() ?: 9876

        if (code.length != 6) {
            _uiState.update { it.copy(snackbarMessage = "Enter the 6-digit code from the receiver") }
            return
        }
        if (ip.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "Enter the receiver's IP address") }
            return
        }
        ensureServiceStarted()
        service?.connectWithPairing(ip, port, code)
    }

    fun reconnectToPairedDevice(device: PairedDevice) {
        if (device.host == null || device.port == null) return
        ensureServiceStarted()
        service?.reconnectToDevice(device)
    }

    fun removePairedDevice(name: String) {
        identityManager.removePairedDevice(name)
        refreshPairedDevices()
    }

    fun disconnect() {
        service?.userDisconnect()
    }

    fun startDiscovery() {
        service?.discovery?.startDiscovery()
    }

    fun stopDiscovery() {
        service?.discovery?.stopDiscovery()
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun refreshPairedDevices() {
        val devices = identityManager.getPairedDevices()
        _uiState.update { it.copy(pairedDevices = devices) }
    }

    override fun onCleared() {
        super.onCleared()
        service?.discovery?.stopDiscovery()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}
