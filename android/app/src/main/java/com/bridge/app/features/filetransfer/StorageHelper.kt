package com.bridge.app.features.filetransfer

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

class StorageHelper(private val context: Context) {
    private val tag = "StorageHelper"

    fun getBridgeDownloadDirectory(): File {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val bridgeDir = File(downloadsDir, "Bridge")
        check(bridgeDir.exists() || bridgeDir.mkdirs()) { "Cannot create download directory" }
        return bridgeDir
    }

    fun prepareDestinationFile(originalName: String): File {
        require(originalName.isNotBlank() && originalName.toByteArray().size <= 200 && originalName != "." && originalName != "..")
        require(originalName.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }) { "Unsafe filename" }
        val dir = getBridgeDownloadDirectory()
        var targetFile = File(dir, originalName)
        if (!targetFile.exists()) return targetFile

        // Resolve collisions: file.ext -> file (1).ext
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex > 0) originalName.substring(dotIndex) else ""

        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(dir, "$baseName ($counter)$extension")
            counter++
        }
        return targetFile
    }

    fun scanMediaFile(file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null
            ) { path, uri ->
                Log.d(tag, "Scanned $path into media library: $uri")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to scan media file", e)
        }
    }

    fun getFileNameAndSizeFromUri(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return Pair(name, size)
    }
}
