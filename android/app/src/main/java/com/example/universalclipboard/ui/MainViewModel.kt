package com.example.universalclipboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.universalclipboard.crypto.IdentityManager
import com.example.universalclipboard.data.ClipboardItem
import com.example.universalclipboard.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Screen state for the main clipboard UI.
 */
data class MainUiState(
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val pairedDevices: List<Pair<String, ByteArray>> = emptyList(),
    val pairingCode: String = "",
    val manualIp: String = "",
    val manualPort: String = "9876",
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_CLIPBOARD_ITEMS = 10
    }

    private val identityManager = IdentityManager(application)
    private val connectionManager = ConnectionManager(identityManager)
    private val discovery = DeviceDiscovery(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Observe connection state
        viewModelScope.launch {
            connectionManager.state.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // Observe discovered devices
        viewModelScope.launch {
            discovery.devices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }

        // Load paired devices
        refreshPairedDevices()
    }

    /**
     * User pastes text into the app.
     */
    fun addClipboardItem(text: String) {
        if (text.isBlank()) return
        _uiState.update { state ->
            val items = state.clipboardItems.toMutableList()
            // Add to front, cap at MAX_CLIPBOARD_ITEMS
            items.add(0, ClipboardItem(text = text))
            if (items.size > MAX_CLIPBOARD_ITEMS) {
                items.removeAt(items.lastIndex)
            }
            state.copy(clipboardItems = items)
        }
    }

    /**
     * Remove a clipboard item.
     */
    fun removeClipboardItem(id: Long) {
        _uiState.update { state ->
            state.copy(clipboardItems = state.clipboardItems.filter { it.id != id })
        }
    }

    /**
     * Send a clipboard item to the connected receiver.
     */
    fun sendClipboardItem(id: Long) {
        val item = _uiState.value.clipboardItems.find { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = connectionManager.sendClipboardText(item.text)
            if (success) {
                _uiState.update { state ->
                    state.copy(
                        clipboardItems = state.clipboardItems.map {
                            if (it.id == id) it.copy(sent = true) else it
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

    /**
     * Update the pairing code input.
     */
    fun updatePairingCode(code: String) {
        if (code.length <= 6 && code.all { it.isDigit() }) {
            _uiState.update { it.copy(pairingCode = code) }
        }
    }

    /**
     * Update manual IP input.
     */
    fun updateManualIp(ip: String) {
        _uiState.update { it.copy(manualIp = ip) }
    }

    /**
     * Update manual port input.
     */
    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    /**
     * Connect to a discovered device with pairing code.
     */
    fun pairWithDevice(device: DiscoveredDevice) {
        val code = _uiState.value.pairingCode
        if (code.length != 6) {
            _uiState.update { it.copy(snackbarMessage = "Enter the 6-digit code from the receiver") }
            return
        }
        connectionManager.connectWithPairing(device.host, device.port, code)
    }

    /**
     * Connect to a manual IP with pairing code.
     */
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
        connectionManager.connectWithPairing(ip, port, code)
    }

    /**
     * Reconnect to a previously paired device.
     */
    fun reconnectToPairedDevice(name: String, host: String, port: Int) {
        val devices = identityManager.getPairedDevices()
        val key = devices[name] ?: return
        connectionManager.reconnect(host, port, name, key)
    }

    /**
     * Disconnect from the current device.
     */
    fun disconnect() {
        connectionManager.disconnect()
    }

    /**
     * Start mDNS discovery.
     */
    fun startDiscovery() {
        discovery.startDiscovery()
    }

    /**
     * Stop mDNS discovery.
     */
    fun stopDiscovery() {
        discovery.stopDiscovery()
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun refreshPairedDevices() {
        val devices = identityManager.getPairedDevices()
        _uiState.update {
            it.copy(pairedDevices = devices.map { (name, key) -> name to key })
        }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stopDiscovery()
        connectionManager.disconnect()
    }
}
