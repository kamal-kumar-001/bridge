package com.bridge.app.core.model

import android.net.Uri

enum class TransferDirection {
    SENDING,
    RECEIVING
}

enum class TransferStatus {
    WAITING_ACCEPT,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class FileTransfer(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.WAITING_ACCEPT,
    val bytesTransferred: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val localUri: Uri? = null,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val startTime: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (fileSize > 0) (bytesTransferred.toFloat() / fileSize).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    val formattedSpeed: String
        get() {
            val mb = speedBytesPerSec / (1024.0 * 1024.0)
            return String.format("%.1f MB/s", mb)
        }

    val formattedSize: String
        get() = formatBytes(fileSize)

    val formattedTransferred: String
        get() = formatBytes(bytesTransferred)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
