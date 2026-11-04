package com.bridge.app.features.pairing

import com.bridge.app.core.model.*
import com.bridge.app.core.network.BridgeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ActivePairingRequest(val peerDevice: PeerDevice, val pin: String, val session: BridgeSession)

class PairingManager(private val pairedDeviceRepository: PairedDeviceRepository, private val localDeviceId: String) {
    private val _pendingPairingRequest = MutableStateFlow<ActivePairingRequest?>(null)
    val pendingPairingRequest = _pendingPairingRequest.asStateFlow()
    private val localAccepted = ConcurrentHashMap.newKeySet<BridgeSession>()
    private val remoteAccepted = ConcurrentHashMap.newKeySet<BridgeSession>()

    fun onHandshake(session: BridgeSession) {
        val peer = session.peerDevice ?: return
        val pin = pairedDeviceRepository.fingerprint(peer.id)
        if (pin != null && pin != session.peerFingerprint) { session.close(); return }
        if (pin == session.peerFingerprint) {
            localAccepted.add(session)
            finish(session)
            session.sendMessage(PairResponseMessage(accepted = true))
        } else {
            if (_pendingPairingRequest.value != null) { session.close(); return }
            _pendingPairingRequest.value = ActivePairingRequest(peer, session.verificationCode, session)
        }
    }

    fun initiatePairing(session: BridgeSession): String = session.verificationCode
    fun handleIncomingPairRequest(session: BridgeSession, msg: PairRequestMessage) { /* Never trust a peer-supplied PIN. */ }

    fun acceptPairing() {
        val request = _pendingPairingRequest.value ?: return
        localAccepted.add(request.session)
        finish(request.session)
        request.session.sendMessage(PairResponseMessage(accepted = true))
        _pendingPairingRequest.value = null
    }

    fun rejectPairing(reason: String = "USER_DECLINED") {
        val request = _pendingPairingRequest.value ?: return
        request.session.sendMessage(PairResponseMessage(accepted = false, reason = reason))
        request.session.close()
        clear(request.session)
    }

    fun handlePairResponse(session: BridgeSession, msg: PairResponseMessage) {
        if (!msg.accepted) { session.close(); clear(session); return }
        if (session.peerDevice == null) { session.close(); return }
        remoteAccepted.add(session)
        finish(session)
    }

    private fun finish(session: BridgeSession) {
        if (session in localAccepted && session in remoteAccepted) {
            val peer = session.peerDevice ?: return
            pairedDeviceRepository.trust(peer, session.peerFingerprint)
            session.authorize()
        }
    }

    fun clear(session: BridgeSession) {
        localAccepted.remove(session); remoteAccepted.remove(session)
        if (_pendingPairingRequest.value?.session == session) _pendingPairingRequest.value = null
    }
}
