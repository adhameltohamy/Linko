package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.FileCategory
import com.example.model.TransferDirection
import com.example.model.TransferHistoryRecord
import com.example.model.TransferItem
import com.example.model.TransferProgressData
import com.example.model.TransferState
import com.example.repository.FileScannerRepository
import com.example.repository.TransferHistoryRepository
import com.example.service.LinkoTransferClient
import com.example.service.LinkoTransferServer
import com.example.service.NetworkUtils
import com.example.service.QRCodeHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ScreenState {
    HOME,
    SEND_PICKER,
    SENDER_WAITING_QR,
    RECEIVE_SCANNER,
    TRANSFER_PROGRESS,
    TRANSFER_COMPLETE,
    HISTORY
}

class LinkoViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val scannerRepo = FileScannerRepository(context)
    private val historyRepo = TransferHistoryRepository(context)
    private val transferServer = LinkoTransferServer(context)
    private val transferClient = LinkoTransferClient(context)

    private val _currentScreen = MutableStateFlow(ScreenState.HOME)
    val currentScreen: StateFlow<ScreenState> = _currentScreen.asStateFlow()

    // Device Network Info
    val deviceName: String = NetworkUtils.getDeviceName()
    private val _localIp = MutableStateFlow(NetworkUtils.getLocalIpAddress())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _wifiName = MutableStateFlow(NetworkUtils.getWifiName(context))
    val wifiName: StateFlow<String> = _wifiName.asStateFlow()

    // File Selection
    private val _availableFiles = MutableStateFlow<List<TransferItem>>(emptyList())
    val availableFiles: StateFlow<List<TransferItem>> = _availableFiles.asStateFlow()

    private val _selectedFileIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileIds: StateFlow<Set<String>> = _selectedFileIds.asStateFlow()

    // Sender Server & QR
    private val _senderQrBitmap = MutableStateFlow<Bitmap?>(null)
    val senderQrBitmap: StateFlow<Bitmap?> = _senderQrBitmap.asStateFlow()

    private val _serverPort = MutableStateFlow(8888)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _serverPin = MutableStateFlow("")
    val serverPin: StateFlow<String> = _serverPin.asStateFlow()

    // Active Transfer Progress
    private val _progressData = MutableStateFlow(TransferProgressData())
    val progressData: StateFlow<TransferProgressData> = _progressData.asStateFlow()

    // History
    val historyList: StateFlow<List<TransferHistoryRecord>> = historyRepo.historyFlow

    init {
        refreshNetworkInfo()
        loadInitialFiles()
        observeServerProgress()
        observeClientProgress()
    }

    fun refreshNetworkInfo() {
        _localIp.value = NetworkUtils.getLocalIpAddress()
        _wifiName.value = NetworkUtils.getWifiName(context)
    }

    private fun loadInitialFiles() {
        viewModelScope.launch {
            val presets = scannerRepo.getPresetFiles()
            val apps = scannerRepo.loadInstalledApps()
            _availableFiles.value = presets + apps
        }
    }

    private fun observeServerProgress() {
        viewModelScope.launch {
            transferServer.progressFlow.collectLatest { prog ->
                if (_progressData.value.direction == TransferDirection.SENDING) {
                    _progressData.value = prog
                    if (prog.state == TransferState.TRANSFERRING && _currentScreen.value == ScreenState.SENDER_WAITING_QR) {
                        _currentScreen.value = ScreenState.TRANSFER_PROGRESS
                    } else if (prog.state == TransferState.COMPLETED && _currentScreen.value == ScreenState.TRANSFER_PROGRESS) {
                        saveTransferRecord(isSender = true, isSuccess = true)
                        _currentScreen.value = ScreenState.TRANSFER_COMPLETE
                    }
                }
            }
        }
    }

    private fun observeClientProgress() {
        viewModelScope.launch {
            transferClient.progressFlow.collectLatest { prog ->
                if (_progressData.value.direction == TransferDirection.RECEIVING) {
                    _progressData.value = prog
                    if (prog.state == TransferState.TRANSFERRING && _currentScreen.value == ScreenState.RECEIVE_SCANNER) {
                        _currentScreen.value = ScreenState.TRANSFER_PROGRESS
                    } else if (prog.state == TransferState.COMPLETED && _currentScreen.value == ScreenState.TRANSFER_PROGRESS) {
                        saveTransferRecord(isSender = false, isSuccess = true)
                        _currentScreen.value = ScreenState.TRANSFER_COMPLETE
                    }
                }
            }
        }
    }

    fun navigateTo(screen: ScreenState) {
        _currentScreen.value = screen
    }

    fun handleBack(): Boolean {
        return when (_currentScreen.value) {
            ScreenState.HOME -> false
            ScreenState.SEND_PICKER -> {
                _currentScreen.value = ScreenState.HOME
                true
            }
            ScreenState.SENDER_WAITING_QR -> {
                transferServer.stopServer()
                _currentScreen.value = ScreenState.SEND_PICKER
                true
            }
            ScreenState.RECEIVE_SCANNER -> {
                _currentScreen.value = ScreenState.HOME
                true
            }
            ScreenState.TRANSFER_PROGRESS -> {
                cancelCurrentTransfer()
                _currentScreen.value = ScreenState.HOME
                true
            }
            ScreenState.TRANSFER_COMPLETE -> {
                _currentScreen.value = ScreenState.HOME
                true
            }
            ScreenState.HISTORY -> {
                _currentScreen.value = ScreenState.HOME
                true
            }
        }
    }

    fun toggleFileSelection(item: TransferItem) {
        val current = _selectedFileIds.value.toMutableSet()
        if (current.contains(item.id)) {
            current.remove(item.id)
        } else {
            current.add(item.id)
        }
        _selectedFileIds.value = current
    }

    fun selectAllInList(list: List<TransferItem>) {
        val current = _selectedFileIds.value.toMutableSet()
        val allSelected = list.all { it.id in current }
        if (allSelected) {
            list.forEach { current.remove(it.id) }
        } else {
            list.forEach { current.add(it.id) }
        }
        _selectedFileIds.value = current
    }

    fun addCustomUris(uris: List<Uri>) {
        viewModelScope.launch {
            val newItems = mutableListOf<TransferItem>()
            for (uri in uris) {
                val item = scannerRepo.resolveUriToTransferItem(uri)
                if (item != null) {
                    newItems.add(item)
                }
            }
            if (newItems.isNotEmpty()) {
                val updated = newItems + _availableFiles.value
                _availableFiles.value = updated
                val selected = _selectedFileIds.value.toMutableSet()
                newItems.forEach { selected.add(it.id) }
                _selectedFileIds.value = selected
            }
        }
    }

    fun startSendingSelectedFiles() {
        val selected = _availableFiles.value.filter { it.id in _selectedFileIds.value }
        if (selected.isEmpty()) return

        refreshNetworkInfo()
        val pin = NetworkUtils.generatePin()
        _serverPin.value = pin

        _progressData.value = TransferProgressData(
            state = TransferState.WAITING_FOR_PEER,
            direction = TransferDirection.SENDING,
            totalFiles = selected.size,
            totalBytes = selected.sumOf { it.sizeBytes }
        )

        transferServer.startServer(
            files = selected,
            pin = pin,
            onReady = { ip, port, p ->
                _serverPort.value = port
                val qrPayload = "linko://$ip:$port?pin=$p"
                viewModelScope.launch {
                    val bitmap = QRCodeHelper.generateQrBitmap(qrPayload)
                    _senderQrBitmap.value = bitmap
                    _currentScreen.value = ScreenState.SENDER_WAITING_QR
                }
            }
        )
    }

    fun handleScannedQr(qrContent: String) {
        // e.g. linko://192.168.1.15:8888?pin=1234 or http://192.168.1.15:8888/info
        try {
            val clean = qrContent.removePrefix("linko://").removePrefix("http://")
            val hostAndPort = clean.substringBefore("?").substringBefore("/")
            val ip = hostAndPort.substringBefore(":")
            val port = hostAndPort.substringAfter(":", "8888").toIntOrNull() ?: 8888
            val pin = if (clean.contains("pin=")) clean.substringAfter("pin=").substringBefore("&") else null

            connectAndReceiveFrom(ip, port, pin)
        } catch (_: Exception) {
            connectAndReceiveFrom(qrContent, 8888, null)
        }
    }

    fun connectAndReceiveFrom(ip: String, port: Int, pin: String?) {
        _progressData.value = TransferProgressData(
            state = TransferState.CONNECTING,
            direction = TransferDirection.RECEIVING
        )
        _currentScreen.value = ScreenState.TRANSFER_PROGRESS

        transferClient.connectAndReceive(
            ip = ip,
            port = port,
            pin = pin,
            onComplete = {
                // Completed
            }
        )
    }

    fun cancelCurrentTransfer() {
        if (_progressData.value.direction == TransferDirection.SENDING) {
            transferServer.stopServer()
        } else {
            transferClient.cancel()
        }
        _progressData.value = _progressData.value.copy(state = TransferState.CANCELLED)
    }

    private fun saveTransferRecord(isSender: Boolean, isSuccess: Boolean) {
        val prog = _progressData.value
        val summary = if (isSender) {
            val selected = _availableFiles.value.filter { it.id in _selectedFileIds.value }
            selected.firstOrNull()?.name ?: "ملفات Linko"
        } else {
            prog.currentFileName.ifBlank { "ملفات مستلمة" }
        }

        val record = TransferHistoryRecord(
            direction = if (isSender) TransferDirection.SENDING else TransferDirection.RECEIVING,
            fileCount = prog.totalFiles.coerceAtLeast(1),
            totalBytes = prog.totalBytes,
            summaryNames = summary,
            peerName = prog.peerName.ifBlank { "جهاز Linko" },
            averageSpeedMBs = 24.5,
            isSuccess = isSuccess
        )
        historyRepo.addRecord(record)
    }

    fun clearHistory() {
        historyRepo.clearHistory()
    }

    override fun onCleared() {
        super.onCleared()
        transferServer.stopServer()
        transferClient.cancel()
    }
}
