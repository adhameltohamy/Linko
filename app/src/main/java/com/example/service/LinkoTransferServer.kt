package com.example.service

import android.content.Context
import com.example.model.TransferItem
import com.example.model.TransferProgressData
import com.example.model.TransferState
import com.example.model.TransferDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LinkoTransferServer(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var speedMonitorJob: Job? = null

    private val _progressFlow = MutableStateFlow(TransferProgressData())
    val progressFlow: StateFlow<TransferProgressData> = _progressFlow.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private val totalBytesTransferred = AtomicLong(0L)
    private var activeFiles: List<TransferItem> = emptyList()
    private var pinCode: String = ""
    private var serverPort: Int = 8888

    fun startServer(
        files: List<TransferItem>,
        port: Int = 8888,
        pin: String = NetworkUtils.generatePin(),
        onReady: (ip: String, port: Int, pin: String) -> Unit
    ) {
        stopServer()
        activeFiles = files
        pinCode = pin
        serverPort = port
        totalBytesTransferred.set(0L)

        val totalBytes = files.sumOf { it.sizeBytes }
        _progressFlow.value = TransferProgressData(
            state = TransferState.WAITING_FOR_PEER,
            direction = TransferDirection.SENDING,
            totalFiles = files.size,
            totalBytes = totalBytes,
            currentFileName = files.firstOrNull()?.name ?: "",
            peerName = "بانتظار اتصال المستلم..."
        )

        serverJob = scope.launch {
            try {
                // Try given port, fallback to port 0 (ephemeral)
                serverSocket = try {
                    ServerSocket(port)
                } catch (_: Exception) {
                    ServerSocket(0)
                }

                val actualPort = serverSocket!!.localPort
                serverPort = actualPort
                val localIp = NetworkUtils.getLocalIpAddress()
                isRunning.set(true)

                launch(Dispatchers.Main) {
                    onReady(localIp, actualPort, pinCode)
                }

                startSpeedTracker(totalBytes)

                while (isActive && isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleClientConnection(clientSocket)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (e: Exception) {
                _progressFlow.value = _progressFlow.value.copy(
                    state = TransferState.FAILED,
                    errorMessage = e.localizedMessage ?: "فشل في تشغيل خادم الإرسال"
                )
            }
        }
    }

    private fun startSpeedTracker(totalBytes: Long) {
        speedMonitorJob?.cancel()
        speedMonitorJob = scope.launch {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive && isRunning.get()) {
                delay(800)
                val currentBytes = totalBytesTransferred.get()
                val currentTime = System.currentTimeMillis()
                val elapsedSec = (currentTime - lastTime).coerceAtLeast(1) / 1000.0

                val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0)
                val speed = deltaBytes / elapsedSec

                val remainingBytes = (totalBytes - currentBytes).coerceAtLeast(0)
                val eta = if (speed > 0) (remainingBytes / speed).toLong() else 0L

                lastBytes = currentBytes
                lastTime = currentTime

                if (_progressFlow.value.state == TransferState.TRANSFERRING) {
                    _progressFlow.value = _progressFlow.value.copy(
                        bytesTransferred = currentBytes,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        scope.launch {
            try {
                socket.use { s ->
                    s.tcpNoDelay = true
                    s.sendBufferSize = 128 * 1024
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: return@launch
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return@launch

                    val method = parts[0]
                    val path = parts[1]

                    when {
                        path.startsWith("/info") -> handleInfoRequest(s)
                        path.startsWith("/download") -> handleDownloadRequest(s, path)
                        path.startsWith("/cancel") -> handleCancelRequest(s)
                        else -> handleWebBrowserRequest(s)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleInfoRequest(socket: Socket) {
        val json = JSONObject().apply {
            put("deviceName", NetworkUtils.getDeviceName())
            put("pin", pinCode)
            put("totalFiles", activeFiles.size)
            put("totalBytes", activeFiles.sumOf { it.sizeBytes })
            val filesArray = JSONArray()
            activeFiles.forEachIndexed { index, item ->
                filesArray.put(JSONObject().apply {
                    put("id", index.toString())
                    put("name", item.name)
                    put("size", item.sizeBytes)
                    put("mimeType", item.mimeType)
                })
            }
            put("files", filesArray)
        }

        val body = json.toString().toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"

        val out = socket.getOutputStream()
        out.write(response.toByteArray())
        out.write(body)
        out.flush()

        _progressFlow.value = _progressFlow.value.copy(
            state = TransferState.TRANSFERRING,
            peerName = "متصل بمستلم ملفات Linko"
        )
    }

    private fun handleDownloadRequest(socket: Socket, fullPath: String) {
        val fileIdStr = fullPath.substringAfter("id=", "").substringBefore("&")
        val index = fileIdStr.toIntOrNull() ?: 0
        val item = activeFiles.getOrNull(index) ?: return

        _progressFlow.value = _progressFlow.value.copy(
            state = TransferState.TRANSFERRING,
            currentFileName = item.name,
            currentFileIndex = index + 1
        )

        var inputStream: InputStream? = null
        try {
            inputStream = openItemInputStream(item)
            if (inputStream == null) {
                val notFound = "HTTP/1.1 404 Not Found\r\n\r\n"
                socket.getOutputStream().write(notFound.toByteArray())
                return
            }

            val encodedName = java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${item.mimeType}\r\n" +
                    "Content-Length: ${item.sizeBytes}\r\n" +
                    "Content-Disposition: attachment; filename=\"$encodedName\"; filename*=UTF-8''$encodedName\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"

            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            out.write(headers.toByteArray(Charsets.UTF_8))

            val buffer = ByteArray(64 * 1024)
            var bytesRead = 0
            while (isRunning.get() && inputStream.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
                totalBytesTransferred.addAndGet(bytesRead.toLong())
            }
            out.flush()

            // If this was the last file and we reached totalBytes
            val totalBytes = activeFiles.sumOf { it.sizeBytes }
            if (totalBytesTransferred.get() >= totalBytes && totalBytes > 0) {
                _progressFlow.value = _progressFlow.value.copy(
                    state = TransferState.COMPLETED,
                    bytesTransferred = totalBytes,
                    speedBytesPerSec = 0.0,
                    etaSeconds = 0
                )
            }
        } finally {
            inputStream?.close()
        }
    }

    private fun handleCancelRequest(socket: Socket) {
        val response = "HTTP/1.1 200 OK\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray())
        stopServer()
        _progressFlow.value = _progressFlow.value.copy(
            state = TransferState.CANCELLED
        )
    }

    private fun handleWebBrowserRequest(socket: Socket) {
        val html = buildString {
            append("<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='utf-8'><title>Linko Web Transfer</title>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            append("<style>body{font-family:sans-serif;background:#0b132b;color:#f8fafc;padding:24px;text-align:center;}")
            append(".card{background:#162038;border-radius:16px;padding:20px;max-width:480px;margin:20px auto;border:1px solid #00f2fe33;}")
            append("h1{color:#00f2fe;} .btn{display:inline-block;background:#00f2fe;color:#0b132b;padding:12px 24px;border-radius:12px;text-decoration:none;font-weight:bold;margin:8px 0;}")
            append("</style></head><body>")
            append("<h1>لينكو (Linko)</h1><p>تمت مشاركة الملفات التالية معك عبر الشبكة المحلية:</p>")
            append("<div class='card'>")
            activeFiles.forEachIndexed { index, file ->
                append("<div style='margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #2d3b55;'>")
                append("<h3>${file.name}</h3>")
                append("<p>${file.formattedSize}</p>")
                append("<a class='btn' href='/download?id=$index' download='${file.name}'>تحميل الملف</a>")
                append("</div>")
            }
            append("</div></body></html>")
        }

        val body = html.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(response.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun openItemInputStream(item: TransferItem): InputStream? {
        // 1. Uri
        if (item.uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(item.uri)
                if (stream != null) return stream
            } catch (_: Exception) {}
        }
        // 2. File Path
        if (!item.filePath.isNullOrEmpty()) {
            val file = File(item.filePath)
            if (file.exists() && file.canRead()) {
                return file.inputStream()
            }
        }
        // 3. Installed App APK via Package Manager
        if (!item.packageName.isNullOrEmpty()) {
            try {
                val appInfo = context.packageManager.getApplicationInfo(item.packageName, 0)
                val apkFile = File(appInfo.sourceDir)
                if (apkFile.exists()) {
                    return apkFile.inputStream()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun stopServer() {
        isRunning.set(false)
        speedMonitorJob?.cancel()
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }
}
