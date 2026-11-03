package com.bridge.app.features.pairing

import android.content.Context
import com.bridge.app.core.model.DeviceType
import com.bridge.app.core.model.OSType
import com.bridge.app.core.model.PeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PairedDeviceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_paired_devices", Context.MODE_PRIVATE)
    private val prefKey = "trusted_devices_json"

    private val _pairedDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PeerDevice>> = _pairedDevices.asStateFlow()

    init {
        loadDevices()
    }

    private fun loadDevices() {
        val jsonStr = prefs.getString(prefKey, null) ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<PeerDevice>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val osStr = obj.optString("os", "UNKNOWN")
                val osType = when {
                    osStr.contains("MAC") -> OSType.MACOS
                    osStr.contains("WIN") -> OSType.WINDOWS
                    osStr.contains("LIN") -> OSType.LINUX
                    osStr.contains("AND") -> OSType.ANDROID
                    else -> OSType.UNKNOWN
                }
                list.add(
                    PeerDevice(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        osType = osType,
                        deviceType = DeviceType.DESKTOP,
                        host = obj.optString("host", ""),
                        port = obj.optInt("port", 52431),
                        isPaired = true,
                        lastSeenTimestamp = obj.optLong("lastSeen", System.currentTimeMillis())
                    )
                )
            }
            _pairedDevices.value = list
        } catch (e: Exception) {
            _pairedDevices.value = emptyList()
        }
    }

    fun isDevicePaired(deviceId: String): Boolean {
        return _pairedDevices.value.any { it.id == deviceId }
    }

    fun addOrUpdatePairedDevice(device: PeerDevice) {
        val current = _pairedDevices.value.filter { it.id != device.id }.toMutableList()
        current.add(device.copy(isPaired = true))
        _pairedDevices.value = current
        saveDevices(current)
    }

    fun revokeDevice(deviceId: String) {
        val current = _pairedDevices.value.filter { it.id != deviceId }
        _pairedDevices.value = current
        saveDevices(current)
    }

    private fun saveDevices(devices: List<PeerDevice>) {
        val arr = JSONArray()
        for (d in devices) {
            val obj = JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("os", d.osType.name)
                put("host", d.host)
                put("port", d.port)
                put("lastSeen", d.lastSeenTimestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString(prefKey, arr.toString()).apply()
    }
}
