package com.bridge.app.core.discovery

import android.content.Context
import com.bridge.app.core.model.PeerDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DualDiscoveryCoordinator(context: Context) : DiscoveryService {
    private val nsdManager = NsdDiscoveryManager(context)
    private val hotspotManager = HotspotDiscoveryManager(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<PeerDevice>> = _discoveredDevices.asStateFlow()

    init {
        scope.launch {
            combineDevices()
        }
    }

    private suspend fun combineDevices() {
        while (true) {
            val nsdList = nsdManager.discoveredDevices.value
            val hotspotList = hotspotManager.discoveredDevices.value

            val merged = mutableMapOf<String, PeerDevice>()
            // Hotspot devices take precedence if both found (direct low-latency path)
            nsdList.forEach { merged[it.id] = it }
            hotspotList.forEach { merged[it.id] = it }

            _discoveredDevices.value = merged.values.toList()
            delay(1000)
        }
    }

    override fun startDiscovery() {
        nsdManager.startDiscovery()
        hotspotManager.startDiscovery()
    }

    override fun stopDiscovery() {
        nsdManager.stopDiscovery()
        hotspotManager.stopDiscovery()
    }

    override fun registerService(port: Int, deviceName: String, deviceId: String) {
        nsdManager.registerService(port, deviceName, deviceId)
        hotspotManager.registerService(port, deviceName, deviceId)
    }

    override fun unregisterService() {
        nsdManager.unregisterService()
        hotspotManager.unregisterService()
    }
}
