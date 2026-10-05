package com.remotecontrollan.network

import android.content.Context
import com.remotecontrollan.model.ConnectionStatus
import com.remotecontrollan.model.StreamMetrics
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.pairing.SecurityManager
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

class WebSocketManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val securityManager = SecurityManager(context)
    val wifiTransport = WiFiTransport(scope)
    val usbTransport = UsbTransport(context, scope)

    private var activeTransport: Transport = wifiTransport

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeTransportType = MutableStateFlow(TransportType.WIFI)
    val activeTransportType: StateFlow<TransportType> = _activeTransportType.asStateFlow()

    private val _metrics = MutableStateFlow(StreamMetrics())
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _receivedClipboard = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val receivedClipboard: SharedFlow<String> = _receivedClipboard.asSharedFlow()

    private var targetHostIp = ""
    private var targetHostPort = NetworkUtils.DEFAULT_WEBSOCKET_PORT
    private var targetHostId = ""
    private var reconnectionJob: Job? = null
    private var pingJob: Job? = null
    private var currentBackoffMs = 1000L

    init {
        // Collect messages from active transport
        scope.launch {
            wifiTransport.incomingMessages.collect { msg ->
                handleIncomingMessage(msg)
            }
        }
        scope.launch {
            usbTransport.incomingMessages.collect { msg ->
                handleIncomingMessage(msg)
            }
        }
    }

    fun connect(hostIp: String, port: Int, hostId: String, pin: String? = null) {
        targetHostIp = hostIp
        targetHostPort = port
        targetHostId = hostId
        currentBackoffMs = 1000L
        reconnectionJob?.cancel()

        scope.launch(Dispatchers.IO) {
            doConnect(pin)
        }
    }

    private suspend fun doConnect(pin: String?) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _statusMessage.value = "Connecting to $targetHostIp..."

        // Check if USB transport is available (Requirement 19: USB = preferred)
        val usbCap = usbTransport.checkUsbCapability()
        var success = false
        if (usbCap.isSupported) {
            _statusMessage.value = "Attempting USB connection..."
            if (usbTransport.connect(targetHostIp, targetHostPort)) {
                activeTransport = usbTransport
                _activeTransportType.value = TransportType.USB
                success = true
                AppLogger.i("WebSocketManager", "Connected via USB transport")
            }
        }

        if (!success) {
            _statusMessage.value = "Connecting over Wi-Fi/LAN..."
            activeTransport = wifiTransport
            _activeTransportType.value = TransportType.WIFI
            success = wifiTransport.connect(targetHostIp, targetHostPort)
        }

        if (!success) {
            _connectionStatus.value = ConnectionStatus.FAILED
            _statusMessage.value = "Failed to connect to $targetHostIp"
            scheduleReconnect(pin)
            return
        }

        // Check authentication / pairing
        val myId = securityManager.getOrCreateDeviceId()
        val savedToken = securityManager.getSavedTokenForHost(targetHostId)

        if (savedToken != null) {
            // Already paired, send auth
            _statusMessage.value = "Authenticating with host..."
            val authMsg = ControlMessage.Auth(
                controllerId = myId,
                token = savedToken,
                deviceName = android.os.Build.MODEL
            )
            activeTransport.send(authMsg)
        } else if (pin != null && pin.isNotBlank()) {
            // First time pairing with submitted PIN
            _statusMessage.value = "Submitting pairing PIN..."
            val pairMsg = ControlMessage.PairingRequest(
                controllerId = myId,
                deviceName = android.os.Build.MODEL,
                pin = pin
            )
            activeTransport.send(pairMsg)
        } else {
            // Pairing PIN required
            _connectionStatus.value = ConnectionStatus.PAIRING_REQUIRED
            _statusMessage.value = "Host pairing PIN required"
        }

        startPingLoop()
    }

    private fun handleIncomingMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.AuthResponse -> {
                if (msg.success) {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _statusMessage.value = "Connected & Authenticated"
                    currentBackoffMs = 1000L
                    AppLogger.i("WebSocketManager", "Host authenticated successfully")
                } else {
                    _connectionStatus.value = ConnectionStatus.PAIRING_REQUIRED
                    _statusMessage.value = "Token expired or invalid: ${msg.message}"
                }
            }
            is ControlMessage.PairingResponse -> {
                if (msg.success && msg.token != null) {
                    securityManager.saveHostToken(targetHostId, msg.token)
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _statusMessage.value = "Pairing successful!"
                    AppLogger.i("WebSocketManager", "Pairing accepted, token saved securely")
                } else {
                    _connectionStatus.value = ConnectionStatus.FAILED
                    _statusMessage.value = "Pairing rejected: ${msg.message}"
                }
            }
            is ControlMessage.Pong -> {
                val rtt = System.currentTimeMillis() - msg.timestamp
                _metrics.value = _metrics.value.copy(latencyMs = rtt, transport = _activeTransportType.value)
            }
            is ControlMessage.ScreenMetadata -> {
                _metrics.value = _metrics.value.copy(
                    resolution = "${msg.width}x${msg.height}"
                )
            }
            is ControlMessage.ClipboardResponse -> {
                _receivedClipboard.tryEmit(msg.text)
                AppLogger.i("WebSocketManager", "Received remote clipboard content")
            }
            else -> Unit
        }
    }

    fun submitPin(pin: String) {
        val myId = securityManager.getOrCreateDeviceId()
        val pairMsg = ControlMessage.PairingRequest(
            controllerId = myId,
            deviceName = android.os.Build.MODEL,
            pin = pin
        )
        scope.launch {
            activeTransport.send(pairMsg)
        }
    }

    fun sendCommand(cmd: ControlMessage) {
        scope.launch {
            activeTransport.send(cmd)
        }
    }

    private fun scheduleReconnect(pin: String?) {
        reconnectionJob?.cancel()
        reconnectionJob = scope.launch(Dispatchers.IO) {
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            val waitSec = currentBackoffMs / 1000
            _statusMessage.value = "Reconnecting in ${waitSec}s..."
            delay(currentBackoffMs)
            currentBackoffMs = min(currentBackoffMs * 2, 16_000L)
            doConnect(pin)
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(3000)
                sendCommand(ControlMessage.Ping(System.currentTimeMillis()))
            }
        }
    }

    fun disconnect() {
        reconnectionJob?.cancel()
        pingJob?.cancel()
        scope.launch {
            activeTransport.disconnect()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _statusMessage.value = "Disconnected"
        }
    }
}
