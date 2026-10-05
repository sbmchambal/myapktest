package com.remotecontrollan.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    const val DEFAULT_WEBSOCKET_PORT = 8887
    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_DISCOVERY_PORT = 8889

    /**
     * Resolves the primary local IPv4 address, prioritizing Hotspot interfaces (ap0, wlan1, etc.)
     * and active Wi-Fi interfaces (wlan0).
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First priority: active Wi-Fi or Hotspot interfaces
            val prioritizedNames = listOf("ap0", "swlan0", "wlan1", "wlan0", "rndis0", "eth0")
            for (name in prioritizedNames) {
                val iface = interfaces.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (iface != null && iface.isUp && !iface.isLoopback) {
                    val addr = getIpv4FromInterface(iface)
                    if (addr != null) return addr
                }
            }

            // Second priority: any non-loopback up IPv4 address
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    val addr = getIpv4FromInterface(iface)
                    if (addr != null && !addr.startsWith("127.")) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("NetworkUtils", "Error resolving local IP", e)
        }
        return "127.0.0.1"
    }

    private fun getIpv4FromInterface(iface: NetworkInterface): String? {
        val addresses = Collections.list(iface.inetAddresses)
        for (addr in addresses) {
            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                return addr.hostAddress
            }
        }
        return null
    }

    /**
     * Checks if the device has an active Wi-Fi connection or local network.
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Calculates subnet broadcast address for UDP discovery beacons.
     */
    fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        return broadcast
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("NetworkUtils", "Failed to get broadcast address, fallback to 255.255.255.255", e)
        }
        return InetAddress.getByName("255.255.255.255")
    }

    /**
     * Guess whether hotspot is enabled based on interface naming (e.g. ap0, swlan0) or standard IP ranges.
     */
    fun isHotspotInterfaceActive(): Boolean {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val name = iface.name.lowercase()
                if ((name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) && iface.isUp) {
                    return true
                }
            }
            val ip = getLocalIpAddress()
            if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.50.")) {
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}
