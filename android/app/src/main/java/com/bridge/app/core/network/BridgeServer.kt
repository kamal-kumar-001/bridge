package com.bridge.app.core.network

import android.util.Log
import com.bridge.app.core.protocol.BridgeProtocol
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class BridgeServer(
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val port: Int = BridgeProtocol.DEFAULT_PORT,
    private val onNewSession: (BridgeSession) -> Unit
) {
    private val tag = "BridgeServer"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                // Bind to wildcard 0.0.0.0 so we listen across Wi-Fi, Hotspot AP, and USB tethering
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                Log.d(tag, "Bridge TCP Server running on 0.0.0.0:$port (Wi-Fi & Hotspot ready)")

                while (isRunning && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        Log.d(tag, "Incoming connection from ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}")
                        val session = BridgeSession(
                            socket = clientSocket,
                            isIncoming = true,
                            localDeviceId = localDeviceId,
                            localDeviceName = localDeviceName,
                            onMessageReceived = { s, msg -> /* Dispatched by session listener */ },
                            onChunkReceived = { s, tid, offset, data -> /* Dispatched */ },
                            onDisconnected = { s -> Log.d(tag, "Session disconnected: ${s.peerDevice?.name}") }
                        )
                        onNewSession(session)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(tag, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind server socket on port $port", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        scope.cancel()
    }
}
