package com.bridge.app.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.bridge.app.core.model.DeviceType
import com.bridge.app.core.model.NetworkMode
import com.bridge.app.core.model.OSType
import com.bridge.app.core.model.PeerDevice
import com.bridge.app.core.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class NsdDiscoveryManager(private val context: Context) : DiscoveryService {
    private val tag = "NsdDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<PeerDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = mutableMapOf<String, PeerDevice>()

    override fun registerService(port: Int, deviceName: String, deviceId: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Bridge-$deviceName"
            serviceType = BridgeProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("os", "ANDROID")
            setAttribute("proto", BridgeProtocol.PROTOCOL_VERSION.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                Log.d(tag, "Bridge NSD service successfully registered: ${registeredInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Registration failed with errorCode: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(tag, "Bridge NSD service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Unregistration failed with errorCode: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Error registering NSD service", e)
        }
    }

    override fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering service", e)
            }
            registrationListener = null
        }
    }

    override fun startDiscovery() {
        acquireMulticastLock()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "NSD Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service found: ${service.serviceName} type=${service.serviceType}")
                if (service.serviceType.contains("bridge")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service lost: ${service.serviceName}")
                val toRemove = deviceMap.entries.firstOrNull { it.value.name == service.serviceName }?.key
                if (toRemove != null) {
                    deviceMap.remove(toRemove)
                    _discoveredDevices.value = deviceMap.values.toList()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "NSD Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Discovery start failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                BridgeProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to start NSD discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host: InetAddress = resolvedInfo.host ?: return
                val port = resolvedInfo.port
                val attributes = resolvedInfo.attributes

                val devId = attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: host.hostAddress ?: return
                val devName = attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: resolvedInfo.serviceName
                val osStr = attributes["os"]?.let { String(it, Charsets.UTF_8).uppercase() } ?: "UNKNOWN"

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
                    host = host.hostAddress ?: host.hostName,
                    port = port,
                    networkMode = NetworkMode.WIFI
                )

                deviceMap[devId] = peer
                _discoveredDevices.value = deviceMap.values.toList()
                Log.d(tag, "Discovered Bridge Peer: $devName ($osType) at ${peer.host}:$port")
            }
        })
    }

    override fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(tag, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("BridgeMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
            multicastLock = null
        }
    }
}
