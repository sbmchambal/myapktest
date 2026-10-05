package com.remotecontrollan.network

import com.remotecontrollan.model.DiscoveredHost
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.utils.AppLogger
import com.remotecontrollan.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class HostDiscoveryBroadcaster(
    private val scope: CoroutineScope,
    private val hostId: String,
    private val deviceName: String,
    private val model: String,
    private val isRooted: Boolean,
    private val port: Int = NetworkUtils.DEFAULT_WEBSOCKET_PORT
) {
    private var broadcastJob: Job? = null

    fun start() {
        if (broadcastJob?.isActive == true) return

        broadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                while (isActive) {
                    val localIp = NetworkUtils.getLocalIpAddress()
                    val payload = JSONObject().apply {
                        put("protocol", "remotecontrollan")
                        put("hostId", hostId)
                        put("deviceName", deviceName)
                        put("model", model)
                        put("ip", localIp)
                        put("port", port)
                        put("isRooted", isRooted)
                        put("timestamp", System.currentTimeMillis())
                    }.toString().toByteArray(Charsets.UTF_8)

                    // Broadcast to subnet
                    try {
                        val broadcastAddr = NetworkUtils.getBroadcastAddress()
                        val packet = DatagramPacket(payload, payload.size, broadcastAddr, NetworkUtils.DEFAULT_DISCOVERY_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // ignore packet error and retry
                    }

                    // Also broadcast to standard 255.255.255.255
                    try {
                        val globalBroadcast = InetAddress.getByName("255.255.255.255")
                        val packet2 = DatagramPacket(payload, payload.size, globalBroadcast, NetworkUtils.DEFAULT_DISCOVERY_PORT)
                        socket.send(packet2)
                    } catch (e: Exception) {
                        // ignore
                    }

                    delay(1500)
                }
            } catch (e: Exception) {
                AppLogger.e("DiscoveryBroadcaster", "Broadcaster error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
        AppLogger.i("DiscoveryBroadcaster", "Started UDP discovery beacon on port ${NetworkUtils.DEFAULT_DISCOVERY_PORT}")
    }

    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        AppLogger.i("DiscoveryBroadcaster", "Stopped UDP discovery beacon")
    }
}

class ControllerDiscoveryScanner(
    private val scope: CoroutineScope
) {
    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private var scanJob: Job? = null
    private var gatewayProbeJob: Job? = null

    fun startScanning() {
        if (scanJob?.isActive == true) return

        scanJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(NetworkUtils.DEFAULT_DISCOVERY_PORT)
                socket.broadcast = true
                socket.soTimeout = 2000

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseAndAddHost(text, packet.address.hostAddress ?: "")
                    } catch (e: SocketTimeoutException) {
                        // timeout is normal, prune stale hosts
                        pruneStaleHosts()
                    } catch (e: Exception) {
                        // ignore individual packet read errors
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("DiscoveryScanner", "Socket bind/receive error: ${e.message}")
            } finally {
                socket?.close()
            }
        }

        // Supplementary Hotspot Gateway Probe:
        // Hotspots commonly assign 192.168.43.1 or 192.168.50.1 as host
        gatewayProbeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                probeHotspotGateway()
                delay(4000)
            }
        }

        AppLogger.i("DiscoveryScanner", "Scanning for remote hosts on LAN...")
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        gatewayProbeJob?.cancel()
        gatewayProbeJob = null
    }

    fun addManualHost(ip: String, port: Int = NetworkUtils.DEFAULT_WEBSOCKET_PORT, name: String = "Manual Host") {
        val host = DiscoveredHost(
            hostId = "manual_$ip",
            deviceName = name,
            model = "Direct IP",
            ipAddress = ip,
            port = port,
            isRooted = true,
            transport = TransportType.WIFI,
            isPaired = false,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        val current = _discoveredHosts.value.toMutableList()
        current.removeAll { it.ipAddress == ip }
        current.add(0, host)
        _discoveredHosts.value = current
    }

    private fun parseAndAddHost(jsonStr: String, senderIp: String) {
        try {
            val json = JSONObject(jsonStr)
            if (json.optString("protocol") != "remotecontrollan") return

            val hostId = json.getString("hostId")
            val name = json.optString("deviceName", "Remote Host")
            val model = json.optString("model", "Android Device")
            val reportedIp = json.optString("ip", senderIp)
            val ip = if (reportedIp.isNotBlank() && reportedIp != "127.0.0.1") reportedIp else senderIp
            val port = json.optInt("port", NetworkUtils.DEFAULT_WEBSOCKET_PORT)
            val isRooted = json.optBoolean("isRooted", false)

            val host = DiscoveredHost(
                hostId = hostId,
                deviceName = name,
                model = model,
                ipAddress = ip,
                port = port,
                isRooted = isRooted,
                transport = TransportType.WIFI,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            val current = _discoveredHosts.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.hostId == hostId || it.ipAddress == ip }
            if (existingIndex >= 0) {
                current[existingIndex] = host
            } else {
                current.add(host)
                AppLogger.i("DiscoveryScanner", "Discovered new host: $name ($ip)")
            }
            _discoveredHosts.value = current
        } catch (e: Exception) {
            // ignore malformed beacon
        }
    }

    private fun pruneStaleHosts() {
        val now = System.currentTimeMillis()
        val current = _discoveredHosts.value.filter { now - it.lastSeenTimestamp < 12_000 }
        if (current.size != _discoveredHosts.value.size) {
            _discoveredHosts.value = current
        }
    }

    private fun probeHotspotGateway() {
        val localIp = NetworkUtils.getLocalIpAddress()
        if (localIp.startsWith("192.168.43.") && localIp != "192.168.43.1") {
            // Controller is connected to Android hotspot! The host is 192.168.43.1
            val existing = _discoveredHosts.value.any { it.ipAddress == "192.168.43.1" }
            if (!existing) {
                addManualHost("192.168.43.1", NetworkUtils.DEFAULT_WEBSOCKET_PORT, "Hotspot Host (Redmi Note 10)")
            }
        }
    }
}
