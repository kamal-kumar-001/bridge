package com.bridge.app.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object CryptoEngine {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private val secureRandom = SecureRandom()

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes)
    }

    /**
     * Calculates a 6-digit Short Authentication String (SAS / PIN)
     * from two device IDs and mutual random nonces.
     */
    fun calculateSasPin(
        localDeviceId: String,
        remoteDeviceId: String,
        localNonce: String,
        remoteNonce: String
    ): String {
        val combined = listOf(localDeviceId, remoteDeviceId, localNonce, remoteNonce).sorted().joinToString(":")
        val hash = sha256Bytes(combined.toByteArray(Charsets.UTF_8))
        val num = ByteBuffer.wrap(hash).int
        val code = abs(num % 900000) + 100000
        return code.toString()
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun encryptAesGcm(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(plaintext)

        val result = ByteBuffer.allocate(iv.size + ciphertext.size)
        result.put(iv)
        result.put(ciphertext)
        return result.array()
    }

    fun decryptAesGcm(encryptedWithIv: ByteArray, secretKey: SecretKey): ByteArray {
        val bb = ByteBuffer.wrap(encryptedWithIv)
        val iv = ByteArray(GCM_IV_LENGTH)
        bb.get(iv)
        val ciphertext = ByteArray(bb.remaining())
        bb.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }
}
