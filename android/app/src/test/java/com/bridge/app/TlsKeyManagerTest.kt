package com.bridge.app

import com.bridge.app.core.crypto.PinnedAliasKeyManager
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

class TlsKeyManagerTest {
    @Test fun selectsOnlyEcIdentityForBothTlsDirections() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private
        val manager = PinnedAliasKeyManager("tls", key, emptyArray<X509Certificate>())
        assertEquals("tls", manager.chooseClientAlias(arrayOf("RSA", "EC"), null, null))
        assertEquals("tls", manager.chooseServerAlias("EC", null, null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertArrayEquals(arrayOf("tls"), manager.getClientAliases("EC", null))
        assertSame(key, manager.getPrivateKey("tls"))
        assertNull(manager.getPrivateKey("legacy"))
        assertNull(manager.getCertificateChain("legacy"))
    }
    @Test fun certificateArrayIsNotMutableThroughCaller() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private
        val manager = PinnedAliasKeyManager("tls", key, emptyArray<X509Certificate>())
        assertNotSame(manager.getCertificateChain("tls"), manager.getCertificateChain("tls"))
    }
}
