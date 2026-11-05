package com.bridge.app.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

class KeyStoreManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge_identity_prefs", Context.MODE_PRIVATE)

    companion object {
        // Keep the legacy key intact. Key authorizations cannot be changed in place.
        const val KEY_ALIAS = "bridge_tls_key_v2"
        private const val PREF_DEVICE_ID = "pref_device_id"
        private const val PREF_DEVICE_NAME = "pref_device_name"
    }

    val deviceId: String
        get() {
            var id = prefs.getString(PREF_DEVICE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(PREF_DEVICE_ID, id).apply()
            }
            return id
        }

    var deviceName: String
        get() {
            val defaultName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            return prefs.getString(PREF_DEVICE_NAME, defaultName) ?: defaultName
        }
        set(value) {
            prefs.edit().putString(PREF_DEVICE_NAME, value).apply()
        }

    fun getOrCreateIdentityKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).run {
            setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            // Conscrypt hashes the TLS transcript before calling NONEwithECDSA.
            setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            build()
        }

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }
}
