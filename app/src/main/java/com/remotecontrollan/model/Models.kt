package com.remotecontrollan.model

import java.io.Serializable

enum class AppMode {
    UNSELECTED,
    HOST,
    CONTROLLER
}

enum class TransportType {
    WIFI,
    USB,
    UNKNOWN
}

enum class RootStatus {
    DETECTED,
    NOT_DETECTED,
    UNKNOWN
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    RECONNECTING,
    PAIRING_REQUIRED,
    CONNECTED,
    FAILED
}

data class DeviceInfo(
    val deviceName: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val screenDensityDpi: Int = 400,
    val rootStatus: RootStatus = RootStatus.UNKNOWN,
    val ipAddress: String = "",
    val port: Int = 8887,
    val isHotspotActive: Boolean = false,
    val transport: TransportType = TransportType.WIFI
) : Serializable

data class StreamProfile(
    val width: Int = 720,
    val height: Int = 1600,
    val fps: Int = 30,
    val bitrateBps: Int = 3_000_000,
    val iFrameIntervalSeconds: Int = 1
)

data class DiscoveredHost(
    val hostId: String,
    val deviceName: String,
    val model: String,
    val ipAddress: String,
    val port: Int,
    val isRooted: Boolean,
    val transport: TransportType = TransportType.WIFI,
    val isPaired: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class PairingInfo(
    val pin: String,
    val hostId: String,
    val token: String? = null,
    val hostIp: String,
    val port: Int
)

data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val isSystemApp: Boolean
)

data class StreamMetrics(
    val fps: Int = 0,
    val latencyMs: Long = 0,
    val resolution: String = "720x1600",
    val bitrateKbps: Int = 0,
    val droppedFrames: Int = 0,
    val transport: TransportType = TransportType.WIFI
)
