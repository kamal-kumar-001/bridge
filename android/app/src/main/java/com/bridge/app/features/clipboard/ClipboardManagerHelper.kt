package com.bridge.app.features.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bridge.app.core.crypto.CryptoEngine
import java.util.concurrent.ConcurrentHashMap

class ClipboardManagerHelper(private val context: Context) {
    private val tag = "ClipboardHelper"
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val seenHashes = ConcurrentHashMap.newKeySet<String>()
    private var lastSyncedHash: String? = null
    private var isSyncEnabled = true

    fun setSyncEnabled(enabled: Boolean) {
        this.isSyncEnabled = enabled
    }

    fun startListening(onTextCopied: (text: String, hash: String) -> Unit) {
        clipboardManager.addPrimaryClipChangedListener {
            if (!isSyncEnabled) return@addPrimaryClipChangedListener

            try {
                val clip = clipboardManager.primaryClip ?: return@addPrimaryClipChangedListener
                if (clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                    if (text.isBlank()) return@addPrimaryClipChangedListener

                    val hash = CryptoEngine.sha256Hex(text)
                    if (seenHashes.contains(hash) || hash == lastSyncedHash) {
                        return@addPrimaryClipChangedListener
                    }

                    recordHash(hash)
                    Log.d(tag, "Local clipboard copied: ${text.take(30)}... [hash=$hash]")
                    onTextCopied(text, hash)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error reading clipboard", e)
            }
        }
    }

    fun setSystemClipboard(text: String, hash: String) {
        if (!isSyncEnabled) return
        if (seenHashes.contains(hash)) return

        recordHash(hash)
        mainHandler.post {
            try {
                val clip = ClipData.newPlainText("Bridge", text)
                clipboardManager.setPrimaryClip(clip)
                Log.d(tag, "Pushed incoming text to Android clipboard: ${text.take(30)}...")
            } catch (e: Exception) {
                Log.e(tag, "Error writing to clipboard", e)
            }
        }
    }

    fun getCurrentClipboardText(): String? {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun recordHash(hash: String) {
        lastSyncedHash = hash
        seenHashes.add(hash)
        // Keep hash set bounded
        if (seenHashes.size > 50) {
            val iter = seenHashes.iterator()
            var count = 0
            while (iter.hasNext() && count < 25) {
                iter.next()
                iter.remove()
                count++
            }
        }
    }
}
