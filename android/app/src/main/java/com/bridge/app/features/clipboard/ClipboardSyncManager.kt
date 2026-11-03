package com.bridge.app.features.clipboard

import android.content.Context
import android.util.Log
import com.bridge.app.core.crypto.CryptoEngine
import com.bridge.app.core.model.ClipboardTextMessage
import com.bridge.app.core.network.BridgeSession

class ClipboardSyncManager(
    context: Context,
    private val localDeviceId: String,
    private val getActiveSession: () -> BridgeSession?
) {
    private val tag = "ClipboardSyncManager"
    val helper = ClipboardManagerHelper(context)

    init {
        helper.startListening { text, hash ->
            broadcastClipboard(text, hash)
        }
    }

    fun broadcastClipboard(text: String, hash: String? = null) {
        val session = getActiveSession() ?: return
        if (!session.isConnected) return

        val finalHash = hash ?: CryptoEngine.sha256Hex(text)
        val msg = ClipboardTextMessage(
            content = text,
            timestamp = System.currentTimeMillis(),
            sourceDeviceId = localDeviceId,
            hash = finalHash
        )
        session.sendMessage(msg)
        Log.d(tag, "Broadcast clipboard text to ${session.peerDevice?.name}")
    }

    fun handleIncomingClipboard(msg: ClipboardTextMessage) {
        if (msg.sourceDeviceId == localDeviceId) return
        helper.setSystemClipboard(msg.content, msg.hash)
    }

    fun syncCurrentClipboardNow() {
        val currentText = helper.getCurrentClipboardText()
        if (!currentText.isNullOrBlank()) {
            broadcastClipboard(currentText)
        }
    }
}
