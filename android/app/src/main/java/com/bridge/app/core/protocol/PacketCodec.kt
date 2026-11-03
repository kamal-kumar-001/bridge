package com.bridge.app.core.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object PacketCodec {
    const val FRAME_JSON: Byte = 0x01
    const val FRAME_FILE_CHUNK: Byte = 0x02
    const val FRAME_ENCRYPTED: Byte = 0x03

    const val MAX_FRAME_SIZE = 100 * 1024 * 1024 // 100MB bound

    fun writeJsonFrame(output: OutputStream, jsonBytes: ByteArray) {
        val totalLength = jsonBytes.size + 1 // +1 for frame type
        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        header.putInt(totalLength)
        header.put(FRAME_JSON)

        synchronized(output) {
            output.write(header.array())
            output.write(jsonBytes)
            output.flush()
        }
    }

    fun writeChunkFrame(
        output: OutputStream,
        transferId: UUID,
        offset: Long,
        chunkBytes: ByteArray,
        chunkLen: Int
    ) {
        val headerLen = 16 + 8 + 4 // UUID(16) + offset(8) + chunkLen(4)
        val totalLength = 1 + headerLen + chunkLen
        val header = ByteBuffer.allocate(5 + headerLen).order(ByteOrder.BIG_ENDIAN)
        header.putInt(totalLength)
        header.put(FRAME_FILE_CHUNK)
        header.putLong(transferId.mostSignificantBits)
        header.putLong(transferId.leastSignificantBits)
        header.putLong(offset)
        header.putInt(chunkLen)

        synchronized(output) {
            output.write(header.array())
            output.write(chunkBytes, 0, chunkLen)
            output.flush()
        }
    }

    sealed class IncomingFrame {
        data class JsonFrame(val jsonString: String) : IncomingFrame()
        data class FileChunkFrame(
            val transferId: UUID,
            val offset: Long,
            val data: ByteArray
        ) : IncomingFrame()
        data class UnknownFrame(val type: Byte, val data: ByteArray) : IncomingFrame()
    }

    fun readFrame(input: InputStream): IncomingFrame? {
        val headerBuf = ByteArray(4)
        if (!readExact(input, headerBuf, 4)) return null
        val totalLength = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN).int
        if (totalLength <= 0 || totalLength > MAX_FRAME_SIZE) return null

        val frameTypeInt = input.read()
        if (frameTypeInt == -1) return null
        val frameType = frameTypeInt.toByte()
        val payloadLen = totalLength - 1

        return when (frameType) {
            FRAME_JSON -> {
                val jsonBuf = ByteArray(payloadLen)
                if (!readExact(input, jsonBuf, payloadLen)) return null
                IncomingFrame.JsonFrame(String(jsonBuf, Charsets.UTF_8))
            }
            FRAME_FILE_CHUNK -> {
                val chunkHeader = ByteArray(28) // 16 + 8 + 4
                if (!readExact(input, chunkHeader, 28)) return null
                val bb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.BIG_ENDIAN)
                val msb = bb.long
                val lsb = bb.long
                val offset = bb.long
                val chunkLen = bb.int
                val transferId = UUID(msb, lsb)

                val dataBuf = ByteArray(chunkLen)
                if (!readExact(input, dataBuf, chunkLen)) return null
                IncomingFrame.FileChunkFrame(transferId, offset, dataBuf)
            }
            else -> {
                val rawBuf = ByteArray(payloadLen)
                if (!readExact(input, rawBuf, payloadLen)) return null
                IncomingFrame.UnknownFrame(frameType, rawBuf)
            }
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var totalRead = 0
        while (totalRead < length) {
            val count = input.read(buffer, totalRead, length - totalRead)
            if (count == -1) return false
            totalRead += count
        }
        return true
    }
}
