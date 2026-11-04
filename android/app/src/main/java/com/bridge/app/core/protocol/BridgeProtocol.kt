package com.bridge.app.core.protocol

import com.bridge.app.core.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

object BridgeProtocol {
    const val SERVICE_TYPE = "_bridge._tcp"
    const val DEFAULT_PORT = 52431
    const val PROTOCOL_VERSION = 2

    private val module = SerializersModule {
        polymorphic(BridgeMessage::class) {
            subclass(HelloMessage::class)
            subclass(HelloAckMessage::class)
            subclass(PairRequestMessage::class)
            subclass(PairResponseMessage::class)
            subclass(ClipboardTextMessage::class)
            subclass(FileOfferMessage::class)
            subclass(FileAcceptMessage::class)
            subclass(FileRejectMessage::class)
            subclass(FileCompleteMessage::class)
            subclass(FileCancelMessage::class)
            subclass(PingMessage::class)
            subclass(PongMessage::class)
            subclass(DisconnectMessage::class)
        }
    }

    val json = Json {
        serializersModule = module
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encodeMessage(message: BridgeMessage): ByteArray {
        val str = json.encodeToString(BridgeMessage.serializer(), message)
        return str.toByteArray(Charsets.UTF_8)
    }

    fun decodeMessage(jsonString: String): BridgeMessage? {
        return try {
            json.decodeFromString(BridgeMessage.serializer(), jsonString)
        } catch (e: Exception) {
            null
        }
    }
}
