package com.bridge.app.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class BridgeClient(
    private val localDeviceId: String,
    private val localDeviceName: String
) {
    private val tag = "BridgeClient"

    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Int = 10000,
        onMessageReceived: (BridgeSession, com.bridge.app.core.model.BridgeMessage) -> Unit,
        onChunkReceived: (BridgeSession, java.util.UUID, Long, ByteArray) -> Unit,
        onDisconnected: (BridgeSession) -> Unit
    ): Result<BridgeSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Connecting to peer at $host:$port...")
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)

            val session = BridgeSession(
                socket = socket,
                isIncoming = false,
                localDeviceId = localDeviceId,
                localDeviceName = localDeviceName,
                onMessageReceived = onMessageReceived,
                onChunkReceived = onChunkReceived,
                onDisconnected = onDisconnected
            )
            session.start()
            Result.success(session)
        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to $host:$port", e)
            Result.failure(e)
        }
    }
}
