package com.bridge.app.core.network

import android.util.Log
import com.bridge.app.core.model.*
import com.bridge.app.core.protocol.BridgeProtocol
import com.bridge.app.core.protocol.PacketCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID

class BridgeSession(
    private val socket: Socket,
    val isIncoming: Boolean,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val onMessageReceived: (BridgeSession, BridgeMessage) -> Unit,
    private val onChunkReceived: (BridgeSession, UUID, Long, ByteArray) -> Unit,
    private val onDisconnected: (BridgeSession) -> Unit
) {
    private val tag = "BridgeSession"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    var peerDevice: PeerDevice? = null
        private set

    val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed && _connectionStatus.value == ConnectionStatus.CONNECTED

    fun start() {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000 // 30 sec read timeout for heartbeat
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()

            // Send initial HELLO
            val hello = HelloMessage(
                deviceId = localDeviceId,
                deviceName = localDeviceName,
                deviceType = DeviceType.PHONE,
                osType = OSType.ANDROID,
                supportedFeatures = listOf("CLIPBOARD", "FILE_TRANSFER")
            )
            sendMessage(hello)

            // Launch read loop
            scope.launch {
                readLoop()
            }

            // Launch heartbeat ping loop
            scope.launch {
                heartbeatLoop()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start session", e)
            close()
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val input = inputStream ?: return@withContext
        while (scope.isActive && !socket.isClosed) {
            try {
                when (val frame = PacketCodec.readFrame(input)) {
                    null -> {
                        Log.d(tag, "EOF or connection closed by peer")
                        break
                    }
                    is PacketCodec.IncomingFrame.JsonFrame -> {
                        val msg = BridgeProtocol.decodeMessage(frame.jsonString)
                        if (msg != null) {
                            handleInternalMessage(msg)
                            onMessageReceived(this@BridgeSession, msg)
                        } else {
                            Log.w(tag, "Unrecognized JSON message: ${frame.jsonString}")
                        }
                    }
                    is PacketCodec.IncomingFrame.FileChunkFrame -> {
                        onChunkReceived(this@BridgeSession, frame.transferId, frame.offset, frame.data)
                    }
                    is PacketCodec.IncomingFrame.UnknownFrame -> {
                        Log.w(tag, "Unknown frame type: ${frame.type}")
                    }
                }
            } catch (e: Exception) {
                if (scope.isActive) {
                    Log.e(tag, "Error reading from socket", e)
                }
                break
            }
        }
        close()
    }

    private fun handleInternalMessage(msg: BridgeMessage) {
        when (msg) {
            is HelloMessage -> {
                val peer = PeerDevice(
                    id = msg.deviceId,
                    name = msg.deviceName,
                    osType = msg.osType,
                    deviceType = msg.deviceType,
                    host = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName,
                    port = socket.port,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                this.peerDevice = peer

                val ack = HelloAckMessage(
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    deviceType = DeviceType.PHONE,
                    osType = OSType.ANDROID,
                    isPaired = peer.isPaired
                )
                sendMessage(ack)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                Log.d(tag, "Received HELLO from ${peer.name} (${peer.osType}). Sent HELLO_ACK.")
            }
            is HelloAckMessage -> {
                val peer = PeerDevice(
                    id = msg.deviceId,
                    name = msg.deviceName,
                    osType = msg.osType,
                    deviceType = msg.deviceType,
                    host = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName,
                    port = socket.port,
                    isPaired = msg.isPaired,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                this.peerDevice = peer
                _connectionStatus.value = ConnectionStatus.CONNECTED
                Log.d(tag, "Connected and handshake complete with ${peer.name} (${peer.osType})")
            }
            is PingMessage -> {
                sendMessage(PongMessage(timestamp = System.currentTimeMillis()))
            }
            is DisconnectMessage -> {
                close()
            }
            else -> {}
        }
    }

    private suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        while (scope.isActive && !socket.isClosed) {
            delay(10000)
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                sendMessage(PingMessage(timestamp = System.currentTimeMillis()))
            }
        }
    }

    fun sendMessage(message: BridgeMessage) {
        val out = outputStream ?: return
        try {
            val jsonBytes = BridgeProtocol.encodeMessage(message)
            PacketCodec.writeJsonFrame(out, jsonBytes)
        } catch (e: Exception) {
            Log.e(tag, "Error sending message: ${message.type}", e)
            close()
        }
    }

    fun sendChunk(transferId: UUID, offset: Long, chunk: ByteArray, length: Int) {
        val out = outputStream ?: return
        try {
            PacketCodec.writeChunkFrame(out, transferId, offset, chunk, length)
        } catch (e: Exception) {
            Log.e(tag, "Error sending chunk for $transferId at offset $offset", e)
            close()
        }
    }

    fun close() {
        if (_connectionStatus.value == ConnectionStatus.DISCONNECTED) return
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.cancel()
        try {
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
        onDisconnected(this)
    }
}
