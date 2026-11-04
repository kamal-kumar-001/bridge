package com.bridge.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class BridgeMessage {
    @kotlinx.serialization.Transient
    open val type: String = ""
}

@Serializable
@SerialName("HELLO")
data class HelloMessage(
    @kotlinx.serialization.Transient override val type: String = "HELLO",
    val version: Int = 2,
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.PHONE,
    val osType: OSType = OSType.ANDROID,
    val protocolVersion: Int = 2,
    val supportedFeatures: List<String> = listOf("CLIPBOARD", "FILE_TRANSFER"),
    val publicKey: String? = null,
    val nonce: String = ""
) : BridgeMessage()

@Serializable
@SerialName("HELLO_ACK")
data class HelloAckMessage(
    @kotlinx.serialization.Transient override val type: String = "HELLO_ACK",
    val version: Int = 2,
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val osType: OSType = OSType.MACOS,
    val isPaired: Boolean = false,
    val publicKey: String? = null,
    val nonce: String = ""
) : BridgeMessage()

@Serializable
@SerialName("PAIR_REQUEST")
data class PairRequestMessage(
    @kotlinx.serialization.Transient override val type: String = "PAIR_REQUEST",
    val pin: String,
    val nonce: String
) : BridgeMessage()

@Serializable
@SerialName("PAIR_RESPONSE")
data class PairResponseMessage(
    @kotlinx.serialization.Transient override val type: String = "PAIR_RESPONSE",
    val accepted: Boolean,
    val nonce: String? = null,
    val reason: String? = null
) : BridgeMessage()

@Serializable
@SerialName("CLIPBOARD_TEXT")
data class ClipboardTextMessage(
    @kotlinx.serialization.Transient override val type: String = "CLIPBOARD_TEXT",
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceDeviceId: String,
    val hash: String
) : BridgeMessage()

@Serializable
@SerialName("FILE_OFFER")
data class FileOfferMessage(
    @kotlinx.serialization.Transient override val type: String = "FILE_OFFER",
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val sha256: String? = null
) : BridgeMessage()

@Serializable
@SerialName("FILE_ACCEPT")
data class FileAcceptMessage(
    @kotlinx.serialization.Transient override val type: String = "FILE_ACCEPT",
    val transferId: String,
    val offset: Long = 0L
) : BridgeMessage()

@Serializable
@SerialName("FILE_REJECT")
data class FileRejectMessage(
    @kotlinx.serialization.Transient override val type: String = "FILE_REJECT",
    val transferId: String,
    val reason: String = "USER_DECLINED"
) : BridgeMessage()

@Serializable
@SerialName("FILE_COMPLETE")
data class FileCompleteMessage(
    @kotlinx.serialization.Transient override val type: String = "FILE_COMPLETE",
    val transferId: String
) : BridgeMessage()

@Serializable
@SerialName("FILE_CANCEL")
data class FileCancelMessage(
    @kotlinx.serialization.Transient override val type: String = "FILE_CANCEL",
    val transferId: String
) : BridgeMessage()

@Serializable
@SerialName("PING")
data class PingMessage(
    @kotlinx.serialization.Transient override val type: String = "PING",
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage()

@Serializable
@SerialName("PONG")
data class PongMessage(
    @kotlinx.serialization.Transient override val type: String = "PONG",
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage()

@Serializable
@SerialName("DISCONNECT")
data class DisconnectMessage(
    @kotlinx.serialization.Transient override val type: String = "DISCONNECT",
    val reason: String = "NORMAL"
) : BridgeMessage()
