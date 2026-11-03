package com.bridge.app.features.filetransfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bridge.app.core.model.*
import com.bridge.app.core.network.BridgeSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager(
    private val context: Context,
    private val getActiveSession: () -> BridgeSession?
) {
    private val tag = "FileTransferManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val storageHelper = StorageHelper(context)

    private val _activeTransfers = MutableStateFlow<List<FileTransfer>>(emptyList())
    val activeTransfers: StateFlow<List<FileTransfer>> = _activeTransfers.asStateFlow()

    private val transferMap = ConcurrentHashMap<String, FileTransfer>()
    private val incomingFileHandles = ConcurrentHashMap<String, RandomAccessFile>()
    private val incomingFiles = ConcurrentHashMap<String, File>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun sendFile(uri: Uri, mimeType: String = "application/octet-stream"): String? {
        val session = getActiveSession() ?: run {
            Log.e(tag, "Cannot send file: no active session")
            return null
        }
        val (name, size) = storageHelper.getFileNameAndSizeFromUri(uri)
        if (size <= 0) {
            Log.e(tag, "Cannot send empty file: $name")
            return null
        }

        val transferId = UUID.randomUUID().toString()
        val transfer = FileTransfer(
            id = transferId,
            fileName = name,
            fileSize = size,
            mimeType = mimeType,
            direction = TransferDirection.SENDING,
            status = TransferStatus.WAITING_ACCEPT,
            localUri = uri
        )

        updateTransfer(transfer)

        val offer = FileOfferMessage(
            transferId = transferId,
            fileName = name,
            fileSize = size,
            mimeType = mimeType
        )
        session.sendMessage(offer)
        Log.d(tag, "Offered file $name ($size bytes) to peer")
        return transferId
    }

    fun handleIncomingOffer(msg: FileOfferMessage, session: BridgeSession) {
        val destFile = storageHelper.prepareDestinationFile(msg.fileName)
        val transfer = FileTransfer(
            id = msg.transferId,
            fileName = msg.fileName,
            fileSize = msg.fileSize,
            mimeType = msg.mimeType,
            direction = TransferDirection.RECEIVING,
            status = TransferStatus.TRANSFERRING,
            localFilePath = destFile.absolutePath
        )
        updateTransfer(transfer)

        try {
            val raf = RandomAccessFile(destFile, "rw")
            raf.setLength(0) // truncate if any
            incomingFileHandles[msg.transferId] = raf
            incomingFiles[msg.transferId] = destFile

            // Accept offer immediately
            val accept = FileAcceptMessage(transferId = msg.transferId, offset = 0)
            session.sendMessage(accept)
            Log.d(tag, "Accepted incoming file offer for ${msg.fileName}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to create destination file", e)
            val reject = FileRejectMessage(transferId = msg.transferId, reason = "STORAGE_ERROR")
            session.sendMessage(reject)
            updateTransfer(transfer.copy(status = TransferStatus.FAILED, errorMessage = e.message))
        }
    }

    fun handleAccept(msg: FileAcceptMessage, session: BridgeSession) {
        val transfer = transferMap[msg.transferId] ?: return
        val uri = transfer.localUri ?: return

        updateTransfer(transfer.copy(status = TransferStatus.TRANSFERRING))

        val job = scope.launch {
            streamFileToPeer(session, transfer, uri, msg.offset)
        }
        activeJobs[msg.transferId] = job
    }

    private suspend fun streamFileToPeer(
        session: BridgeSession,
        transfer: FileTransfer,
        uri: Uri,
        startOffset: Long
    ) = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream")

            if (startOffset > 0) {
                inputStream.skip(startOffset)
            }

            val buffer = ByteArray(65536) // 64KB chunks
            var bytesSent = startOffset
            val tidUuid = UUID.fromString(transfer.id)
            val startTime = System.currentTimeMillis()
            var lastUpdate = startTime

            while (isActive && bytesSent < transfer.fileSize) {
                val read = inputStream.read(buffer)
                if (read == -1) break

                session.sendChunk(tidUuid, bytesSent, buffer, read)
                bytesSent += read

                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 200 || bytesSent >= transfer.fileSize) {
                    val elapsedSec = (now - startTime) / 1000.0
                    val speed = if (elapsedSec > 0) (bytesSent - startOffset) / elapsedSec else 0.0
                    updateTransfer(
                        transfer.copy(
                            bytesTransferred = bytesSent,
                            speedBytesPerSec = speed,
                            status = if (bytesSent >= transfer.fileSize) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                        )
                    )
                    lastUpdate = now
                }
            }

            if (bytesSent >= transfer.fileSize) {
                session.sendMessage(FileCompleteMessage(transferId = transfer.id))
                Log.d(tag, "Completed sending file ${transfer.fileName}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error streaming file to peer", e)
            updateTransfer(transfer.copy(status = TransferStatus.FAILED, errorMessage = e.message))
            session.sendMessage(FileCancelMessage(transferId = transfer.id))
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                // ignore
            }
            activeJobs.remove(transfer.id)
        }
    }

    fun handleIncomingChunk(transferId: UUID, offset: Long, data: ByteArray) {
        val tid = transferId.toString()
        val raf = incomingFileHandles[tid] ?: return
        val transfer = transferMap[tid] ?: return

        try {
            synchronized(raf) {
                raf.seek(offset)
                raf.write(data)
            }

            val newTransferred = (transfer.bytesTransferred + data.size).coerceAtMost(transfer.fileSize)
            val elapsedSec = (System.currentTimeMillis() - transfer.startTime) / 1000.0
            val speed = if (elapsedSec > 0) newTransferred / elapsedSec else 0.0

            updateTransfer(
                transfer.copy(
                    bytesTransferred = newTransferred,
                    speedBytesPerSec = speed
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "Error writing incoming chunk", e)
        }
    }

    fun handleComplete(msg: FileCompleteMessage) {
        val tid = msg.transferId
        val raf = incomingFileHandles.remove(tid)
        try {
            raf?.close()
        } catch (e: Exception) {
            // ignore
        }

        val file = incomingFiles.remove(tid)
        if (file != null && file.exists()) {
            storageHelper.scanMediaFile(file)
        }

        val transfer = transferMap[tid] ?: return
        updateTransfer(
            transfer.copy(
                status = TransferStatus.COMPLETED,
                bytesTransferred = transfer.fileSize
            )
        )
        Log.d(tag, "Transfer complete: ${transfer.fileName} saved to ${file?.absolutePath}")
    }

    fun cancelTransfer(transferId: String) {
        val job = activeJobs.remove(transferId)
        job?.cancel()

        val raf = incomingFileHandles.remove(transferId)
        try {
            raf?.close()
        } catch (e: Exception) {
            // ignore
        }

        val session = getActiveSession()
        session?.sendMessage(FileCancelMessage(transferId = transferId))

        val transfer = transferMap[transferId] ?: return
        updateTransfer(transfer.copy(status = TransferStatus.CANCELLED))
    }

    private fun updateTransfer(transfer: FileTransfer) {
        transferMap[transfer.id] = transfer
        _activeTransfers.value = transferMap.values.sortedByDescending { it.startTime }
    }
}
