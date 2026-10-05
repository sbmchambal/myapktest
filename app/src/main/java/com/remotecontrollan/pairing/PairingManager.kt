package com.remotecontrollan.pairing

import android.content.Context
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class PairingManager(private val context: Context) {

    private val securityManager = SecurityManager(context)

    private val _currentPin = MutableStateFlow(securityManager.generate6DigitPin())
    val currentPin: StateFlow<String> = _currentPin.asStateFlow()

    private val _pairedClientsCount = MutableStateFlow(securityManager.getPairedControllers().size)
    val pairedClientsCount: StateFlow<Int> = _pairedClientsCount.asStateFlow()

    /**
     * Regenerates a new 6-digit PIN.
     */
    fun regeneratePin(): String {
        val newPin = securityManager.generate6DigitPin()
        _currentPin.value = newPin
        AppLogger.i("PairingManager", "Generated new pairing PIN")
        return newPin
    }

    /**
     * Verifies pairing PIN from incoming controller request.
     * If valid, generates and registers a new secure session token.
     */
    fun verifyPinAndIssueToken(controllerId: String, deviceName: String, submittedPin: String): String? {
        if (submittedPin.trim() == _currentPin.value.trim()) {
            val token = securityManager.generateSecureToken()
            securityManager.storePairedController(controllerId, token, deviceName)
            _pairedClientsCount.value = securityManager.getPairedControllers().size
            AppLogger.i("PairingManager", "Pairing successful for $deviceName ($controllerId)")
            // Rotate PIN after successful pairing for forward secrecy
            regeneratePin()
            return token
        }
        AppLogger.w("PairingManager", "Invalid pairing PIN attempt from $controllerId")
        return null
    }

    /**
     * Authenticates an incoming WebSocket or HTTP command token.
     */
    fun authenticate(controllerId: String, token: String): Boolean {
        return securityManager.isValidToken(controllerId, token)
    }

    /**
     * Generates a safe QR code JSON payload containing connection endpoints & temporary pairing PIN.
     * Does NOT contain root credentials or persistent private keys.
     */
    fun generateQrPayload(ipAddress: String, port: Int, deviceName: String): String {
        val json = JSONObject()
        json.put("protocol", "remotecontrollan")
        json.put("version", 1)
        json.put("hostId", securityManager.getOrCreateDeviceId())
        json.put("deviceName", deviceName)
        json.put("ip", ipAddress)
        json.put("port", port)
        json.put("pin", _currentPin.value)
        return json.toString()
    }

    fun unpairController(controllerId: String) {
        securityManager.revokeController(controllerId)
        _pairedClientsCount.value = securityManager.getPairedControllers().size
    }

    fun unpairAll() {
        securityManager.revokeAllControllers()
        _pairedClientsCount.value = 0
    }
}
