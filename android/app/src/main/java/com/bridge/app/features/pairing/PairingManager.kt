package com.bridge.app.features.pairing

import com.bridge.app.core.crypto.CryptoEngine
import com.bridge.app.core.model.PairRequestMessage
import com.bridge.app.core.model.PairResponseMessage
import com.bridge.app.core.model.PeerDevice
import com.bridge.app.core.network.BridgeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActivePairingRequest(
    val peerDevice: PeerDevice,
    val pin: String,
    val session: BridgeSession
)

class PairingManager(
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val localDeviceId: String
) {
    private val _pendingPairingRequest = MutableStateFlow<ActivePairingRequest?>(null)
    val pendingPairingRequest: StateFlow<ActivePairingRequest?> = _pendingPairingRequest.asStateFlow()

    fun initiatePairing(session: BridgeSession): String {
        val peer = session.peerDevice ?: return ""
        val localNonce = CryptoEngine.generateNonce()
        val pin = CryptoEngine.calculateSasPin(localDeviceId, peer.id, localNonce, "")

        val msg = PairRequestMessage(pin = pin, nonce = localNonce)
        session.sendMessage(msg)
        return pin
    }

    fun handleIncomingPairRequest(session: BridgeSession, msg: PairRequestMessage) {
        val peer = session.peerDevice ?: return
        _pendingPairingRequest.value = ActivePairingRequest(
            peerDevice = peer,
            pin = msg.pin,
            session = session
        )
    }

    fun acceptPairing() {
        val active = _pendingPairingRequest.value ?: return
        val resp = PairResponseMessage(accepted = true, nonce = CryptoEngine.generateNonce())
        active.session.sendMessage(resp)
        pairedDeviceRepository.addOrUpdatePairedDevice(active.peerDevice)
        _pendingPairingRequest.value = null
    }

    fun rejectPairing(reason: String = "USER_DECLINED") {
        val active = _pendingPairingRequest.value ?: return
        val resp = PairResponseMessage(accepted = false, reason = reason)
        active.session.sendMessage(resp)
        _pendingPairingRequest.value = null
    }

    fun handlePairResponse(session: BridgeSession, msg: PairResponseMessage) {
        val peer = session.peerDevice ?: return
        if (msg.accepted) {
            pairedDeviceRepository.addOrUpdatePairedDevice(peer)
        }
    }
}
