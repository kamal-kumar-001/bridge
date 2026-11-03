package com.bridge.app.core.discovery

import android.content.Context
import android.util.Log
import com.bridge.app.core.model.DeviceType
import com.bridge.app.core.model.NetworkMode
import com.bridge.app.core.model.OSType
import com.bridge.app.core.model.PeerDevice
import com.bridge.app.core.protocol.BridgeProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class HotspotDiscoveryManager(private val context: Context) : DiscoveryService {
    private val tag = "HotspotDiscovery"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<PeerDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = mutableMapOf<String, PeerDevice>()
    private var isRunning = false
    private var udpSocket: DatagramSocket? = null

    private var localPort = BridgeProtocol.DEFAULT_PORT
    private var localDeviceName = "Android Device"
    private var localDeviceId = ""

    override fun registerService(port: Int, deviceName: String, deviceId: String) {
        this.localPort = port
        this.localDeviceName = deviceName
        this.localDeviceId = deviceId
    }

    override fun unregisterService() {
        // No-op for direct hotspot
    }

    override fun startDiscovery() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            listenForUdpBeacons()
        }

        scope.launch {
            broadcastPresencePeriodically()
        }
    }

    override fun stopDiscovery() {
        isRunning = false
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing UDP socket", e)
        }
        udpSocket = null
        scope.cancel()
    }

    private suspend fun listenForUdpBeacons() = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket(BridgeProtocol.DEFAULT_PORT).apply {
                broadcast = true
                reuseAddress = true
            }
            udpSocket = socket

            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            Log.d(tag, "Listening for UDP Hotspot beacons on port ${BridgeProtocol.DEFAULT_PORT}")

            while (isRunning) {
                try {
                    socket.receive(packet)
                    val jsonStr = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    handleIncomingBeacon(jsonStr, packet.address)
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start UDP beacon listener", e)
        }
    }

    private fun handleIncomingBeacon(jsonStr: String, senderAddress: InetAddress) {
        try {
            val json = JSONObject(jsonStr)
            if (json.optString("proto") != "bridge") return

            val devId = json.optString("id")
            if (devId == localDeviceId) return // Ignore self-echo

            val devName = json.optString("name", "Unknown Peer")
            val osStr = json.optString("os", "UNKNOWN").uppercase()
            val port = json.optInt("port", BridgeProtocol.DEFAULT_PORT)

            val osType = when {
                osStr.contains("MAC") -> OSType.MACOS
                osStr.contains("WIN") -> OSType.WINDOWS
                osStr.contains("LIN") -> OSType.LINUX
                osStr.contains("AND") -> OSType.ANDROID
                else -> OSType.UNKNOWN
            }

            val peer = PeerDevice(
                id = devId,
                name = devName,
                osType = osType,
                deviceType = DeviceType.DESKTOP,
                host = senderAddress.hostAddress ?: senderAddress.hostName,
                port = port,
                networkMode = NetworkMode.HOTSPOT,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            deviceMap[devId] = peer
            _discoveredDevices.value = deviceMap.values.toList()
            Log.d(tag, "Discovered Peer via Hotspot Beacon: $devName ($osType) at ${peer.host}:$port")
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming beacon: $jsonStr", e)
        }
    }

    private suspend fun broadcastPresencePeriodically() = withContext(Dispatchers.IO) {
        val socket = DatagramSocket().apply { broadcast = true }

        while (isRunning) {
            try {
                val beacon = JSONObject().apply {
                    put("proto", "bridge")
                    put("name", localDeviceName)
                    put("os", "ANDROID")
                    put("port", localPort)
                    put("id", localDeviceId)
                }.toString().toByteArray(Charsets.UTF_8)

                val broadcastAddresses = getBroadcastAddresses()
                for (bcast in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(beacon, beacon.size, bcast, BridgeProtocol.DEFAULT_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // ignore network drop
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error broadcasting presence", e)
            }
            delay(3000)
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            list.add(InetAddress.getByName("192.168.43.255")) // Standard Android AP broadcast
            list.add(InetAddress.getByName("172.20.10.255"))

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue

                for (ifAddr in netIf.interfaceAddresses) {
                    ifAddr.broadcast?.let { list.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting broadcast addresses", e)
        }
        return list.distinct()
    }
}
