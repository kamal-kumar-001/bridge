package com.bridge.app.core.discovery

import com.bridge.app.core.model.PeerDevice
import kotlinx.coroutines.flow.StateFlow

interface DiscoveryService {
    val discoveredDevices: StateFlow<List<PeerDevice>>
    fun startDiscovery()
    fun stopDiscovery()
    fun registerService(port: Int, deviceName: String, deviceId: String)
    fun unregisterService()
}
