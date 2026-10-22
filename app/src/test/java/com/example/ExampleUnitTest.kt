package com.example

import com.example.model.TransferDirection
import com.example.model.TransferItem
import com.example.model.TransferProgressData
import com.example.model.TransferState
import com.example.service.NetworkUtils
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", TransferItem.formatFileSize(0))
        assertEquals("500 B", TransferItem.formatFileSize(500))
        assertEquals("1.0 KB", TransferItem.formatFileSize(1024))
        assertEquals("10.5 MB", TransferItem.formatFileSize((10.5 * 1024 * 1024).toLong()))
        assertEquals("2.50 GB", TransferItem.formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testTransferProgressCalculations() {
        val progressData = TransferProgressData(
            state = TransferState.TRANSFERRING,
            direction = TransferDirection.SENDING,
            currentFileName = "movie.mp4",
            currentFileIndex = 1,
            totalFiles = 2,
            bytesTransferred = 50 * 1024 * 1024L,
            totalBytes = 100 * 1024 * 1024L,
            speedBytesPerSec = 25.0 * 1024 * 1024,
            etaSeconds = 2
        )

        assertEquals(0.5f, progressData.overallProgress, 0.001f)
        assertEquals("25.0 MB/s", progressData.formattedSpeed)
        assertEquals("02 ث", progressData.formattedEta)
    }

    @Test
    fun testPinGeneration() {
        val pin = NetworkUtils.generatePin()
        assertEquals(4, pin.length)
        assertTrue(pin.toInt() in 1000..9999)
    }
}
