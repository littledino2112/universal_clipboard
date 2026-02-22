package com.example.universalclipboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.universalclipboard.ui.MainViewModel
import com.example.universalclipboard.ui.screens.MainScreen
import com.example.universalclipboard.ui.theme.UniversalClipboardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                        onClearSnackbar = viewModel::clearSnackbar
                    )
                }
            }
        }
    }
}
