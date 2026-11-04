package com.bridge.app.features.clipboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.asStateFlow
import com.bridge.app.core.crypto.CryptoEngine
import com.bridge.app.core.model.ClipboardTextMessage
import com.bridge.app.core.network.BridgeSession

class ClipboardSyncManager(
    context: Context,
    private val localDeviceId: String,
    private val getActiveSession: () -> BridgeSession?
) {
    private val prefs = context.getSharedPreferences("bridge_sync", Context.MODE_PRIVATE)
    private val _enabled = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean("enabled", value).apply()
        helper.setSyncEnabled(value)
    }
    private val tag = "ClipboardSyncManager"
    val helper = ClipboardManagerHelper(context)

    init {
        helper.setSyncEnabled(_enabled.value)
        helper.startListening { text, hash ->
            broadcastClipboard(text, hash)
        }
    }

    fun broadcastClipboard(text: String, hash: String? = null) {
        val session = getActiveSession() ?: return
        if (!session.authenticated || text.toByteArray().size > 256 * 1024) return

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
        if (msg.sourceDeviceId == localDeviceId || msg.content.toByteArray().size > 256 * 1024 || CryptoEngine.sha256Hex(msg.content) != msg.hash) return
        helper.setSystemClipboard(msg.content, msg.hash)
    }

    fun syncCurrentClipboardNow() {
        val currentText = helper.getCurrentClipboardText()
        if (!currentText.isNullOrBlank()) {
            broadcastClipboard(currentText)
        }
    }
}
