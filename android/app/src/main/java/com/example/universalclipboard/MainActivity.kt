package com.example.universalclipboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.universalclipboard.ui.MainViewModel
import com.example.universalclipboard.ui.screens.MainScreen
import com.example.universalclipboard.ui.theme.UniversalClipboardTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, service will work either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            UniversalClipboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: MainViewModel = viewModel()
                    val uiState = viewModel.uiState.collectAsStateWithLifecycle()

                    MainScreen(
                        uiState = uiState.value,
                        onPasteFromClipboard = viewModel::addClipboardItem,
                        onSendItem = viewModel::sendClipboardItem,
                        onDeleteItem = viewModel::removeClipboardItem,
                        onPairingCodeChange = viewModel::updatePairingCode,
                        onManualIpChange = viewModel::updateManualIp,
                        onManualPortChange = viewModel::updateManualPort,
                        onPairManual = viewModel::pairWithManualAddress,
                        onDisconnect = viewModel::disconnect,
                        onStartDiscovery = viewModel::startDiscovery,
                        onPairWithDevice = viewModel::pairWithDevice,
                        onReconnectPairedDevice = viewModel::reconnectToPairedDevice,
                        onRemovePairedDevice = viewModel::removePairedDevice,
                        onClearSnackbar = viewModel::clearSnackbar
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
