package com.example.model

import android.net.Uri

enum class FileCategory(val titleAr: String, val titleEn: String) {
    APPS("تطبيقات", "Apps"),
    VIDEOS("فيديوهات", "Videos"),
    IMAGES("صور", "Images"),
    AUDIO("صوتيات", "Audio"),
    DOCUMENTS("مستندات", "Documents"),
    ARCHIVES("أرشيفات", "Archives"),
    ALL("الكل", "All")
}

data class TransferItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String = "*/*",
    val uri: Uri? = null,
    val filePath: String? = null,
    val category: FileCategory = FileCategory.ALL,
    val packageName: String? = null, // for installed apps
    val isSelected: Boolean = false,
    val progressPercent: Float = 0f,
    val isCompleted: Boolean = false
) {
    val formattedSize: String
        get() = formatFileSize(sizeBytes)

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}

enum class TransferDirection {
    SENDING,
    RECEIVING
}

enum class TransferState {
    IDLE,
    WAITING_FOR_PEER,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferProgressData(
    val state: TransferState = TransferState.IDLE,
    val direction: TransferDirection = TransferDirection.SENDING,
    val currentFileName: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long = 0L,
    val peerName: String = "",
    val errorMessage: String? = null
) {
    val overallProgress: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val formattedSpeed: String
        get() {
            val mbPerSec = speedBytesPerSec / (1024.0 * 1024.0)
            return if (mbPerSec >= 0.1) {
                String.format(java.util.Locale.US, "%.1f MB/s", mbPerSec)
            } else {
                val kbPerSec = speedBytesPerSec / 1024.0
                String.format(java.util.Locale.US, "%.0f KB/s", kbPerSec)
            }
        }

    val formattedEta: String
        get() {
            if (etaSeconds <= 0) return "--:--"
            val minutes = etaSeconds / 60
            val seconds = etaSeconds % 60
            return if (minutes > 0) {
                String.format(java.util.Locale.US, "%02d:%02d د", minutes, seconds)
            } else {
                String.format(java.util.Locale.US, "%02d ث", seconds)
            }
        }

    val formattedTransferred: String
        get() = "${TransferItem.formatFileSize(bytesTransferred)} / ${TransferItem.formatFileSize(totalBytes)}"
}

data class TransferHistoryRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val direction: TransferDirection,
    val fileCount: Int,
    val totalBytes: Long,
    val summaryNames: String,
    val peerName: String,
    val averageSpeedMBs: Double,
    val isSuccess: Boolean
)
