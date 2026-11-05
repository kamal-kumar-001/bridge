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

    @Volatile var authenticated: Boolean = false
        private set
    var peerFingerprint: String = ""
        private set
    var verificationCode: String = ""
        private set
    private val localNonce = com.bridge.app.core.crypto.CryptoEngine.generateNonce()
    private val writer = FrameWriter { error ->
        recordFailure("Connection write failed", error)
        Log.e(tag, "Connection writer failed", error)
        close()
    }
    private var started = false
    @Volatile var failure: String? = null
        private set

    private fun recordFailure(stage: String, error: Exception) {
        failure = "$stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}".take(350)
    }

    fun authorize() {
        authenticated = true
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch { startTransport() }
    }

    private fun startTransport() {
        try {
            val tls = socket as javax.net.ssl.SSLSocket
            socket.soTimeout = 15000
            tls.startHandshake()
            peerFingerprint = com.bridge.app.core.crypto.TlsIdentity.fingerprint(tls.session.peerCertificates[0])
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
                supportedFeatures = listOf("CLIPBOARD", "FILE_TRANSFER"),
                nonce = localNonce
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
            recordFailure("TLS / initial handshake failed", e)
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
                            if (!authenticated && msg !is HelloMessage && msg !is HelloAckMessage &&
                                msg !is PairRequestMessage && msg !is PairResponseMessage &&
                                msg !is PingMessage && msg !is PongMessage && msg !is DisconnectMessage) {
                                throw SecurityException("Pairing required")
                            }
                            handleInternalMessage(msg)
                            onMessageReceived(this@BridgeSession, msg)
                        } else {
                            throw IllegalArgumentException("Invalid control message")
                        }
                    }
                    is PacketCodec.IncomingFrame.FileChunkFrame -> {
                        check(authenticated) { "Pairing required" }
                        onChunkReceived(this@BridgeSession, frame.transferId, frame.offset, frame.data)
                    }
                    is PacketCodec.IncomingFrame.UnknownFrame -> {
                        Log.w(tag, "Unknown frame type: ${frame.type}")
                    }
                }
            } catch (e: Exception) {
                if (scope.isActive) {
                    recordFailure("Connection read failed", e)
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
                check(peerDevice == null && msg.version == 2 && msg.nonce.matches(Regex("[0-9a-f]{32}")) && msg.deviceId.length in 1..128 && msg.deviceName.length <= 100) { "Invalid or legacy handshake" }
                val tls = socket as javax.net.ssl.SSLSocket
                val localFingerprint = com.bridge.app.core.crypto.TlsIdentity.fingerprint(tls.session.localCertificates[0])
                val transcript = listOf(localFingerprint + ":" + localNonce, peerFingerprint + ":" + msg.nonce).sorted().joinToString("|")
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
                verificationCode = "%06d".format((java.nio.ByteBuffer.wrap(hash).int.toLong() and 0xffffffffL) % 1000000)
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
                _connectionStatus.value = ConnectionStatus.PAIRING
                Log.d(tag, "Received HELLO from ${peer.name} (${peer.osType}). Sent HELLO_ACK.")
            }
            is HelloAckMessage -> { check(msg.version == 2) { "Legacy peer refused" } }
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
            if (_connectionStatus.value == ConnectionStatus.CONNECTED || _connectionStatus.value == ConnectionStatus.PAIRING) {
                sendMessage(PingMessage(timestamp = System.currentTimeMillis()))
            }
        }
    }

    fun sendMessage(message: BridgeMessage) {
        if (!authenticated && message !is HelloMessage && message !is HelloAckMessage &&
            message !is PairRequestMessage && message !is PairResponseMessage &&
            message !is PingMessage && message !is PongMessage && message !is DisconnectMessage) return
        val out = outputStream ?: return
        val jsonBytes = BridgeProtocol.encodeMessage(message)
        try {
            writer.write { PacketCodec.writeJsonFrame(out, jsonBytes) }
        } catch (error: Exception) {
            // The writer records the failure and closes the session. Never write on the caller thread.
        }
    }

    fun sendChunk(transferId: UUID, offset: Long, chunk: ByteArray, length: Int) {
        check(authenticated) { "Pairing required" }
        val out = outputStream ?: throw java.io.IOException("Disconnected")
        require(length in 1..65536 && length <= chunk.size)
        val owned = chunk.copyOf(length)
        // Called by the file IO worker, not the UI. Wait bounds memory and protects buffer reuse.
        writer.write(wait = true) {
            check(authenticated) { "Pairing required" }
            PacketCodec.writeChunkFrame(out, transferId, offset, owned, owned.size)
        }
    }

    fun close() {
        if (_connectionStatus.value == ConnectionStatus.DISCONNECTED) return
        authenticated = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.cancel()
        writer.close()
        try {
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
        onDisconnected(this)
    }
}
