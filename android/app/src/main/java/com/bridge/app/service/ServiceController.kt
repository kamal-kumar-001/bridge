package com.bridge.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bridge.app.core.crypto.KeyStoreManager
import com.bridge.app.core.discovery.DiscoveryService
import com.bridge.app.core.discovery.DualDiscoveryCoordinator
import com.bridge.app.core.model.*
import com.bridge.app.core.network.BridgeClient
import com.bridge.app.core.network.BridgeServer
import com.bridge.app.core.network.BridgeSession
import com.bridge.app.core.protocol.BridgeProtocol
import com.bridge.app.features.clipboard.ClipboardSyncManager
import com.bridge.app.features.filetransfer.FileTransferManager
import com.bridge.app.features.pairing.PairedDeviceRepository
import com.bridge.app.features.pairing.PairingManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ServiceController private constructor(private val appContext: Context) {
    private val tag = "ServiceController"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val keyStoreManager = KeyStoreManager(appContext)
    val pairedDeviceRepo = PairedDeviceRepository(appContext)
    val pairingManager = PairingManager(pairedDeviceRepo, keyStoreManager.deviceId)

    private val discoveryCoordinator: DiscoveryService = DualDiscoveryCoordinator(appContext)
    val discoveredDevices: StateFlow<List<PeerDevice>> = discoveryCoordinator.discoveredDevices

    private val _activeSession = MutableStateFlow<BridgeSession?>(null)
    val activeSession: StateFlow<BridgeSession?> = _activeSession.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<PeerDevice?>(null)
    val connectedDevice: StateFlow<PeerDevice?> = _connectedDevice.asStateFlow()

    val clipboardManager = ClipboardSyncManager(
        context = appContext,
        localDeviceId = keyStoreManager.deviceId,
        getActiveSession = { _activeSession.value }
    )

    val fileTransferManager = FileTransferManager(
        context = appContext,
        getActiveSession = { _activeSession.value }
    )

    private var bridgeServer: BridgeServer? = null
    private val bridgeClient = BridgeClient(keyStoreManager.deviceId, keyStoreManager.deviceName)

    fun start() {
        Log.d(tag, "Starting ServiceController")
        startServer()
        startDiscovery()
    }

    private fun startServer() {
        bridgeServer?.stop()
        bridgeServer = BridgeServer(
            localDeviceId = keyStoreManager.deviceId,
            localDeviceName = keyStoreManager.deviceName,
            port = BridgeProtocol.DEFAULT_PORT
        ) { session ->
            handleIncomingSession(session)
        }.also { it.start() }
    }

    private fun startDiscovery() {
        discoveryCoordinator.registerService(
            BridgeProtocol.DEFAULT_PORT,
            keyStoreManager.deviceName,
            keyStoreManager.deviceId
        )
        discoveryCoordinator.startDiscovery()
    }

    private fun handleIncomingSession(session: BridgeSession) {
        scope.launch {
            attachSession(session)
        }
    }

    fun connectToDevice(device: PeerDevice) {
        scope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            val result = bridgeClient.connect(
                host = device.host,
                port = device.port,
                onMessageReceived = { s, msg -> handleMessage(s, msg) },
                onChunkReceived = { s, tid, offset, data -> fileTransferManager.handleIncomingChunk(tid, offset, data) },
                onDisconnected = { s -> onSessionDisconnected(s) }
            )

            result.onSuccess { session ->
                attachSession(session)
            }.onFailure {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    private fun attachSession(session: BridgeSession) {
        _activeSession.value?.close()
        _activeSession.value = session

        scope.launch {
            session.connectionStatus.collect { status ->
                _connectionStatus.value = status
                if (status == ConnectionStatus.CONNECTED) {
                    _connectedDevice.value = session.peerDevice
                } else if (status == ConnectionStatus.DISCONNECTED) {
                    if (_activeSession.value == session) {
                        _activeSession.value = null
                        _connectedDevice.value = null
                    }
                }
            }
        }

        session.start()
    }

    private fun handleMessage(session: BridgeSession, msg: BridgeMessage) {
        when (msg) {
            is HelloMessage -> {
                _connectedDevice.value = session.peerDevice
            }
            is HelloAckMessage -> {
                _connectedDevice.value = session.peerDevice
            }
            is PairRequestMessage -> {
                pairingManager.handleIncomingPairRequest(session, msg)
            }
            is PairResponseMessage -> {
                pairingManager.handlePairResponse(session, msg)
            }
            is ClipboardTextMessage -> {
                clipboardManager.handleIncomingClipboard(msg)
            }
            is FileOfferMessage -> {
                fileTransferManager.handleIncomingOffer(msg, session)
            }
            is FileAcceptMessage -> {
                fileTransferManager.handleAccept(msg, session)
            }
            is FileCompleteMessage -> {
                fileTransferManager.handleComplete(msg)
            }
            is FileCancelMessage -> {
                fileTransferManager.cancelTransfer(msg.transferId)
            }
            else -> {}
        }
    }

    private fun onSessionDisconnected(session: BridgeSession) {
        if (_activeSession.value == session) {
            _activeSession.value = null
            _connectedDevice.value = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    fun disconnect() {
        _activeSession.value?.sendMessage(DisconnectMessage())
        _activeSession.value?.close()
        _activeSession.value = null
        _connectedDevice.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun sendFile(uri: Uri, mimeType: String = "application/octet-stream") = fileTransferManager.sendFile(uri, mimeType)

    fun sendText(text: String) = clipboardManager.broadcastClipboard(text)

    companion object {
        @Volatile
        private var instance: ServiceController? = null

        fun getInstance(context: Context): ServiceController {
            return instance ?: synchronized(this) {
                instance ?: ServiceController(context.applicationContext).also { instance = it }
            }
        }
    }
}
