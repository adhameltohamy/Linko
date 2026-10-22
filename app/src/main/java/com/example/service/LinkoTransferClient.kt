package com.example.service

import android.content.Context
import android.os.Environment
import com.example.model.TransferDirection
import com.example.model.TransferProgressData
import com.example.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LinkoTransferClient(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var transferJob: Job? = null
    private var speedMonitorJob: Job? = null

    private val _progressFlow = MutableStateFlow(TransferProgressData())
    val progressFlow: StateFlow<TransferProgressData> = _progressFlow.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private val totalBytesReceived = AtomicLong(0L)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun connectAndReceive(
        ip: String,
        port: Int,
        pin: String? = null,
        onComplete: (receivedFiles: List<File>) -> Unit = {}
    ) {
        cancel()
        isCancelled.set(false)
        totalBytesReceived.set(0L)

        _progressFlow.value = TransferProgressData(
            state = TransferState.CONNECTING,
            direction = TransferDirection.RECEIVING,
            peerName = "جاري الاتصال بالجهاز المرسل ($ip:$port)..."
        )

        transferJob = scope.launch {
            val savedFiles = mutableListOf<File>()
            try {
                // 1. Fetch info
                val infoUrl = "http://$ip:$port/info"
                val infoRequest = Request.Builder().url(infoUrl).build()
                val infoResponse = okHttpClient.newCall(infoRequest).execute()

                if (!infoResponse.isSuccessful) {
                    throw Exception("تعذر الاتصال بخادم النقل (${infoResponse.code})")
                }

                val bodyStr = infoResponse.body?.string() ?: throw Exception("استجابة فارغة من المرسل")
                val json = JSONObject(bodyStr)
                val peerName = json.optString("deviceName", "جهاز Linko")
                val totalBytes = json.optLong("totalBytes", 0L)
                val filesArray = json.optJSONArray("files") ?: throw Exception("لا توجد ملفات مرسلة")

                _progressFlow.value = _progressFlow.value.copy(
                    state = TransferState.TRANSFERRING,
                    peerName = peerName,
                    totalFiles = filesArray.length(),
                    totalBytes = totalBytes
                )

                startSpeedTracker(totalBytes)

                // 2. Prepare output folder (Downloads/Linko or app storage)
                val downloadDir = getOutputFolder()

                // 3. Download each file
                for (i in 0 until filesArray.length()) {
                    if (isCancelled.get()) break
                    val fileObj = filesArray.getJSONObject(i)
                    val id = fileObj.getString("id")
                    val name = fileObj.getString("name")
                    val size = fileObj.getLong("size")

                    _progressFlow.value = _progressFlow.value.copy(
                        currentFileName = name,
                        currentFileIndex = i + 1
                    )

                    val downloadUrl = "http://$ip:$port/download?id=$id"
                    val fileReq = Request.Builder().url(downloadUrl).build()
                    val fileRes = okHttpClient.newCall(fileReq).execute()

                    if (!fileRes.isSuccessful) {
                        throw Exception("فشل في تحميل الملف $name")
                    }

                    val outputFile = getUniqueFile(downloadDir, name)
                    val inputStream: InputStream = fileRes.body?.byteStream()
                        ?: throw Exception("فشل تدفق البيانات للملف $name")

                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            if (isCancelled.get()) break
                            fos.write(buffer, 0, read)
                            totalBytesReceived.addAndGet(read.toLong())
                        }
                        fos.flush()
                    }

                    if (!isCancelled.get()) {
                        savedFiles.add(outputFile)
                    }
                }

                if (!isCancelled.get()) {
                    _progressFlow.value = _progressFlow.value.copy(
                        state = TransferState.COMPLETED,
                        bytesTransferred = totalBytes,
                        speedBytesPerSec = 0.0,
                        etaSeconds = 0
                    )
                    launch(Dispatchers.Main) {
                        onComplete(savedFiles)
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    _progressFlow.value = _progressFlow.value.copy(
                        state = TransferState.FAILED,
                        errorMessage = e.localizedMessage ?: "حدث خطأ أثناء نقل الملفات"
                    )
                }
            }
        }
    }

    private fun startSpeedTracker(totalBytes: Long) {
        speedMonitorJob?.cancel()
        speedMonitorJob = scope.launch {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive && !isCancelled.get()) {
                delay(800)
                val currentBytes = totalBytesReceived.get()
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

    private fun getOutputFolder(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val linkoFolder = File(downloads, "Linko")
        if (!linkoFolder.exists()) {
            linkoFolder.mkdirs()
        }
        if (linkoFolder.canWrite()) {
            return linkoFolder
        }
        // Fallback to app external files dir
        val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val fallback = File(appDir, "Linko")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun getUniqueFile(folder: File, name: String): File {
        var file = File(folder, name)
        if (!file.exists()) return file

        val nameWithoutExt = name.substringBeforeLast(".")
        val ext = if (name.contains(".")) ".${name.substringAfterLast(".")}" else ""
        var count = 1
        while (file.exists()) {
            file = File(folder, "$nameWithoutExt ($count)$ext")
            count++
        }
        return file
    }

    fun cancel() {
        isCancelled.set(true)
        speedMonitorJob?.cancel()
        transferJob?.cancel()
        _progressFlow.value = _progressFlow.value.copy(
            state = TransferState.CANCELLED
        )
    }
}
