package com.bridge.app.core.crypto

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Self-signed certificates encrypt transport; application pairing pins the peer certificate. */
object TlsIdentity {
    fun context(): SSLContext {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, null)
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()); chain[0].checkValidity()
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = checkClientTrusted(chain, authType)
        }
        return SSLContext.getInstance("TLS").apply { init(factory.keyManagers, arrayOf(trust), SecureRandom()) }
    }

    fun fingerprint(certificate: java.security.cert.Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}
