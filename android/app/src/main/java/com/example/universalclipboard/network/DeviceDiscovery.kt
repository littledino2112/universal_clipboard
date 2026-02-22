package com.example.universalclipboard.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a discovered clipboard receiver device on the network.
 */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int
)

/**
 * Discovers Universal Clipboard receivers on the local network using mDNS/NSD.
 */
class DeviceDiscovery(context: Context) {

    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val SERVICE_TYPE = "_uclip._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

    private val deviceMap = mutableMapOf<String, DiscoveredDevice>()
    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "mDNS discovery started")
            isDiscovering = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
            // Resolve the service to get host and port
            nsdManager.resolveService(serviceInfo, resolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            deviceMap.remove(serviceInfo.serviceName)
            _devices.value = deviceMap.values.toList()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS discovery stopped")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: $errorCode")
            isDiscovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: $errorCode")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host.hostAddress ?: return
            val device = DiscoveredDevice(
                name = serviceInfo.serviceName,
                host = host,
                port = serviceInfo.port
            )
            Log.d(TAG, "Resolved: $device")
            deviceMap[serviceInfo.serviceName] = device
            _devices.value = deviceMap.values.toList()
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        deviceMap.clear()
        _devices.value = emptyList()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Stop discovery error: ${e.message}")
        }
    }
}
