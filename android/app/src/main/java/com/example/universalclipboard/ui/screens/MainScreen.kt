package com.example.universalclipboard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.universalclipboard.crypto.PairedDevice
import com.example.universalclipboard.data.ClipboardItem
import com.example.universalclipboard.network.ConnectionState
import com.example.universalclipboard.ui.MainUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onPasteFromClipboard: (String) -> Unit,
    onSendItem: (Long) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onManualIpChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onPairManual: () -> Unit,
    onDisconnect: () -> Unit,
    onStartDiscovery: () -> Unit,
    onPairWithDevice: (com.example.universalclipboard.network.DiscoveredDevice) -> Unit,
    onReconnectPairedDevice: (PairedDevice) -> Unit,
    onRemovePairedDevice: (String) -> Unit,
    onClearSnackbar: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            onClearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Universal Clipboard") },
                actions = {
                    // Connection status indicator
                    val statusColor = when (uiState.connectionState) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    val statusText = when (val state = uiState.connectionState) {
                        is ConnectionState.Connected -> state.deviceName
                        is ConnectionState.Connecting -> "Connecting..."
                        is ConnectionState.Error -> "Error"
                        ConnectionState.Disconnected -> "Disconnected"
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val text = clipboardManager.getText()?.text
                    if (text != null) {
                        onPasteFromClipboard(text)
                    }
                }
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Paste from clipboard")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection section
            ConnectionSection(
                uiState = uiState,
                onPairingCodeChange = onPairingCodeChange,
                onManualIpChange = onManualIpChange,
                onManualPortChange = onManualPortChange,
                onPairManual = onPairManual,
                onDisconnect = onDisconnect,
                onStartDiscovery = onStartDiscovery,
                onPairWithDevice = onPairWithDevice,
                onReconnectPairedDevice = onReconnectPairedDevice,
                onRemovePairedDevice = onRemovePairedDevice
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Clipboard items
            Text(
                text = "Clipboard Items (${uiState.clipboardItems.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (uiState.clipboardItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tap the paste button to add clipboard content",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.clipboardItems, key = { it.id }) { item ->
                        ClipboardItemCard(
                            item = item,
                            isConnected = uiState.connectionState is ConnectionState.Connected,
                            onSend = { onSendItem(item.id) },
                            onDelete = { onDeleteItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    uiState: MainUiState,
    onPairingCodeChange: (String) -> Unit,
    onManualIpChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onPairManual: () -> Unit,
    onDisconnect: () -> Unit,
    onStartDiscovery: () -> Unit,
    onPairWithDevice: (com.example.universalclipboard.network.DiscoveredDevice) -> Unit,
    onReconnectPairedDevice: (PairedDevice) -> Unit,
    onRemovePairedDevice: (String) -> Unit
) {
    when (uiState.connectionState) {
        is ConnectionState.Connected -> {
            // Connected: show status and disconnect button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Connected to:", style = MaterialTheme.typography.labelSmall)
                    Text(
                        uiState.connectionState.deviceName,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                OutlinedButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }
        is ConnectionState.Connecting -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Connecting...")
            }
        }
        else -> {
            // Disconnected: show pairing UI
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connect to Receiver", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Pairing code input
                OutlinedTextField(
                    value = uiState.pairingCode,
                    onValueChange = onPairingCodeChange,
                    label = { Text("6-digit pairing code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Manual IP connection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.manualIp,
                        onValueChange = onManualIpChange,
                        label = { Text("IP Address") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uiState.manualPort,
                        onValueChange = onManualPortChange,
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPairManual,
                        modifier = Modifier.weight(1f),
                        enabled = uiState.pairingCode.length == 6 && uiState.manualIp.isNotBlank()
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = onStartDiscovery,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Network")
                    }
                }

                // Show discovered devices
                if (uiState.discoveredDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Discovered Devices:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    uiState.discoveredDevices.forEach { device ->
                        OutlinedButton(
                            onClick = { onPairWithDevice(device) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            enabled = uiState.pairingCode.length == 6
                        ) {
                            Text("${device.name} (${device.host}:${device.port})")
                        }
                    }
                }

                // Paired devices
                if (uiState.pairedDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Paired Devices:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    uiState.pairedDevices.forEach { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (device.host != null && device.port != null) {
                                        Text(
                                            "${device.host}:${device.port}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                Button(
                                    onClick = { onReconnectPairedDevice(device) },
                                    enabled = device.host != null && device.port != null,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Reconnect")
                                }
                                IconButton(onClick = { onRemovePairedDevice(device.name) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Forget device",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                // Error state
                if (uiState.connectionState is ConnectionState.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.connectionState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardItemCard(
    item: ClipboardItem,
    isConnected: Boolean,
    onSend: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.preview(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.timestamp
                        .atZone(ZoneId.systemDefault())
                        .format(timeFormatter) +
                            if (item.sent) " - Sent" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.sent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(
                onClick = onSend,
                enabled = isConnected
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
