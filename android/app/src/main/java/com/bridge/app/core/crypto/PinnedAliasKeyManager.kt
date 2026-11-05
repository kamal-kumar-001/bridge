package com.bridge.app.core.crypto

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager

/** Select only Bridge's TLS identity, never another key in AndroidKeyStore. */
class PinnedAliasKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>
) : X509ExtendedKeyManager() {
    private fun supported(type: String?) = type == key.algorithm
    override fun getClientAliases(type: String?, issuers: Array<out Principal>?) =
        if (supported(type)) arrayOf(alias) else null
    override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = getClientAliases(type, issuers)
    override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        if (types?.any { supported(it) } == true) alias else null
    override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
        if (supported(type)) alias else null
    override fun getCertificateChain(requested: String?) = if (requested == alias) chain.clone() else null
    override fun getPrivateKey(requested: String?) = if (requested == alias) key else null
}
