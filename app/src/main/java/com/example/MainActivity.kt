package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.model.TransferItem
import com.example.ui.LinkoViewModel
import com.example.ui.ScreenState
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ReceiveScannerScreen
import com.example.ui.screens.SendFilePickerScreen
import com.example.ui.screens.SenderWaitingScreen
import com.example.ui.screens.TransferCompleteScreen
import com.example.ui.screens.TransferProgressScreen
import com.example.ui.theme.LinkoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LinkoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShareIntent(intent)

        setContent {
            LinkoTheme(darkTheme = true) {
                // Force RTL for Arabic UX
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LinkoApp(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (uri != null) {
                    viewModel.addCustomUris(listOf(uri))
                    viewModel.navigateTo(ScreenState.SEND_PICKER)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    viewModel.addCustomUris(uris)
                    viewModel.navigateTo(ScreenState.SEND_PICKER)
                }
            }
        }
    }
}

@Composable
fun LinkoApp(viewModel: LinkoViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val wifiName by viewModel.wifiName.collectAsState()
    val availableFiles by viewModel.availableFiles.collectAsState()
    val selectedFileIds by viewModel.selectedFileIds.collectAsState()
    val qrBitmap by viewModel.senderQrBitmap.collectAsState()
    val port by viewModel.serverPort.collectAsState()
    val pin by viewModel.serverPin.collectAsState()
    val progressData by viewModel.progressData.collectAsState()
    val historyList by viewModel.historyList.collectAsState()

    BackHandler(enabled = currentScreen != ScreenState.HOME) {
        viewModel.handleBack()
    }

    Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
        when (screen) {
            ScreenState.HOME -> {
                HomeScreen(
                    deviceName = viewModel.deviceName,
                    wifiName = wifiName,
                    localIp = localIp,
                    historyList = historyList,
                    onSendClick = {
                        viewModel.refreshNetworkInfo()
                        viewModel.navigateTo(ScreenState.SEND_PICKER)
                    },
                    onReceiveClick = {
                        viewModel.refreshNetworkInfo()
                        viewModel.navigateTo(ScreenState.RECEIVE_SCANNER)
                    },
                    onHistoryClick = {
                        viewModel.navigateTo(ScreenState.HISTORY)
                    }
                )
            }

            ScreenState.SEND_PICKER -> {
                SendFilePickerScreen(
                    availableFiles = availableFiles,
                    selectedFileIds = selectedFileIds,
                    onToggleSelectFile = { item -> viewModel.toggleFileSelection(item) },
                    onSelectAllInCurrentCategory = { list -> viewModel.selectAllInList(list) },
                    onAddCustomUris = { uris -> viewModel.addCustomUris(uris) },
                    onProceedToSend = { viewModel.startSendingSelectedFiles() },
                    onBackClick = { viewModel.handleBack() }
                )
            }

            ScreenState.SENDER_WAITING_QR -> {
                val selectedItems = availableFiles.filter { it.id in selectedFileIds }
                SenderWaitingScreen(
                    qrBitmap = qrBitmap,
                    ipAddress = localIp,
                    port = port,
                    pin = pin,
                    wifiName = wifiName,
                    selectedFiles = selectedItems,
                    onCancelClick = { viewModel.handleBack() }
                )
            }

            ScreenState.RECEIVE_SCANNER -> {
                ReceiveScannerScreen(
                    localIp = localIp,
                    onQrScanned = { qr -> viewModel.handleScannedQr(qr) },
                    onManualConnect = { ip, p, pinCode ->
                        viewModel.connectAndReceiveFrom(ip, p, pinCode)
                    },
                    onBackClick = { viewModel.handleBack() }
                )
            }

            ScreenState.TRANSFER_PROGRESS -> {
                TransferProgressScreen(
                    progressData = progressData,
                    onCancelTransfer = {
                        viewModel.cancelCurrentTransfer()
                        viewModel.navigateTo(ScreenState.HOME)
                    }
                )
            }

            ScreenState.TRANSFER_COMPLETE -> {
                TransferCompleteScreen(
                    progressData = progressData,
                    onDoneClick = { viewModel.navigateTo(ScreenState.HOME) },
                    onSendMoreClick = { viewModel.navigateTo(ScreenState.SEND_PICKER) }
                )
            }

            ScreenState.HISTORY -> {
                HistoryScreen(
                    historyList = historyList,
                    onClearHistory = { viewModel.clearHistory() },
                    onBackClick = { viewModel.handleBack() }
                )
            }
        }
    }
}
