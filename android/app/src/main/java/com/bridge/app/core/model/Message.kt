package com.bridge.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class BridgeMessage {
    abstract val type: String
}

@Serializable
@SerialName("HELLO")
data class HelloMessage(
    override val type: String = "HELLO",
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.PHONE,
    val osType: OSType = OSType.ANDROID,
    val protocolVersion: Int = 1,
    val supportedFeatures: List<String> = listOf("CLIPBOARD", "FILE_TRANSFER"),
    val publicKey: String? = null
) : BridgeMessage()

@Serializable
@SerialName("HELLO_ACK")
data class HelloAckMessage(
    override val type: String = "HELLO_ACK",
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val osType: OSType = OSType.MACOS,
    val isPaired: Boolean = false,
    val publicKey: String? = null
) : BridgeMessage()

@Serializable
@SerialName("PAIR_REQUEST")
data class PairRequestMessage(
    override val type: String = "PAIR_REQUEST",
    val pin: String,
    val nonce: String
) : BridgeMessage()

@Serializable
@SerialName("PAIR_RESPONSE")
data class PairResponseMessage(
    override val type: String = "PAIR_RESPONSE",
    val accepted: Boolean,
    val nonce: String? = null,
    val reason: String? = null
) : BridgeMessage()

@Serializable
@SerialName("CLIPBOARD_TEXT")
data class ClipboardTextMessage(
    override val type: String = "CLIPBOARD_TEXT",
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceDeviceId: String,
    val hash: String
) : BridgeMessage()

@Serializable
@SerialName("FILE_OFFER")
data class FileOfferMessage(
    override val type: String = "FILE_OFFER",
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val sha256: String? = null
) : BridgeMessage()

@Serializable
@SerialName("FILE_ACCEPT")
data class FileAcceptMessage(
    override val type: String = "FILE_ACCEPT",
    val transferId: String,
    val offset: Long = 0L
) : BridgeMessage()

@Serializable
@SerialName("FILE_REJECT")
data class FileRejectMessage(
    override val type: String = "FILE_REJECT",
    val transferId: String,
    val reason: String = "USER_DECLINED"
) : BridgeMessage()

@Serializable
@SerialName("FILE_COMPLETE")
data class FileCompleteMessage(
    override val type: String = "FILE_COMPLETE",
    val transferId: String
) : BridgeMessage()

@Serializable
@SerialName("FILE_CANCEL")
data class FileCancelMessage(
    override val type: String = "FILE_CANCEL",
    val transferId: String
) : BridgeMessage()

@Serializable
@SerialName("PING")
data class PingMessage(
    override val type: String = "PING",
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage()

@Serializable
@SerialName("PONG")
data class PongMessage(
    override val type: String = "PONG",
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage()

@Serializable
@SerialName("DISCONNECT")
data class DisconnectMessage(
    override val type: String = "DISCONNECT",
    val reason: String = "NORMAL"
) : BridgeMessage()
