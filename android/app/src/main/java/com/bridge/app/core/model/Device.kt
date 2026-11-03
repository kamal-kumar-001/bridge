package com.bridge.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class OSType {
    MACOS,
    WINDOWS,
    LINUX,
    ANDROID,
    UNKNOWN;

    val displayName: String
        get() = when (this) {
            MACOS -> "macOS"
            WINDOWS -> "Windows"
            LINUX -> "Linux"
            ANDROID -> "Android"
            UNKNOWN -> "Unknown"
        }

    val iconEmoji: String
        get() = when (this) {
            MACOS -> "🍎"
            WINDOWS -> "🪟"
            LINUX -> "🐧"
            ANDROID -> "📱"
            UNKNOWN -> "💻"
        }
}

@Serializable
enum class DeviceType {
    DESKTOP,
    PHONE,
    TABLET,
    UNKNOWN
}

enum class ConnectionStatus {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    PAIRING,
    CONNECTED
}

enum class NetworkMode {
    WIFI,
    HOTSPOT,
    USB,
    UNKNOWN;

    val label: String
        get() = when (this) {
            WIFI -> "Wi-Fi"
            HOTSPOT -> "Phone Hotspot"
            USB -> "USB Cable"
            UNKNOWN -> "Local Network"
        }
}

@Serializable
data class PeerDevice(
    val id: String,
    val name: String,
    val osType: OSType,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val host: String,
    val port: Int = 52431,
    val isPaired: Boolean = false,
    val networkMode: NetworkMode = NetworkMode.WIFI,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
