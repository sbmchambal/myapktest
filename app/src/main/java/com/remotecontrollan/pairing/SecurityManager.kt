package com.remotecontrollan.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.remotecontrollan.utils.AppLogger
import java.security.SecureRandom
import java.util.UUID

class SecurityManager(private val context: Context) {

    private val secureRandom = SecureRandom()
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "remote_control_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w("SecurityManager", "Failed to init EncryptedSharedPreferences, using private fallback", e)
            context.getSharedPreferences("remote_control_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    /**
     * Generates a cryptographically strong 6-digit PIN (e.g. 100000 - 999999).
     */
    fun generate6DigitPin(): String {
        val pinInt = 100_000 + secureRandom.nextInt(900_000)
        return pinInt.toString()
    }

    /**
     * Generates a high-entropy session authentication token.
     */
    fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates or retrieves a unique persistent device ID for this phone.
     */
    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString("device_uuid", null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_uuid", newId).apply()
        return newId
    }

    /**
     * Saves an authorized controller token.
     */
    fun storePairedController(controllerId: String, token: String, deviceName: String) {
        prefs.edit()
            .putString("paired_token_$controllerId", token)
            .putString("paired_name_$controllerId", deviceName)
            .putLong("paired_time_$controllerId", System.currentTimeMillis())
            .apply()
        AppLogger.i("SecurityManager", "Stored paired controller: $controllerId ($deviceName)")
    }

    /**
     * Validates if a token matches the stored token for a given controller ID.
     */
    fun isValidToken(controllerId: String, token: String): Boolean {
        if (controllerId.isBlank() || token.isBlank()) return false
        val stored = prefs.getString("paired_token_$controllerId", null) ?: return false
        // Constant-time equals comparison to prevent timing attacks
        return constantTimeEquals(stored, token)
    }

    /**
     * Checks if this controller has already paired with the target host.
     */
    fun getSavedTokenForHost(hostId: String): String? {
        return prefs.getString("host_token_$hostId", null)
    }

    fun saveHostToken(hostId: String, token: String) {
        prefs.edit().putString("host_token_$hostId", token).apply()
    }

    /**
     * Revokes a specific paired controller.
     */
    fun revokeController(controllerId: String) {
        prefs.edit()
            .remove("paired_token_$controllerId")
            .remove("paired_name_$controllerId")
            .remove("paired_time_$controllerId")
            .apply()
        AppLogger.i("SecurityManager", "Revoked controller: $controllerId")
    }

    /**
     * Revokes all paired controllers.
     */
    fun revokeAllControllers() {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith("paired_")) {
                editor.remove(key)
            }
        }
        editor.apply()
        AppLogger.i("SecurityManager", "Revoked all paired controllers")
    }

    fun getPairedControllers(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val all = prefs.all
        for ((k, v) in all) {
            if (k.startsWith("paired_token_")) {
                val controllerId = k.removePrefix("paired_token_")
                val name = prefs.getString("paired_name_$controllerId", "Unknown Controller") ?: "Unknown"
                result.add(Pair(controllerId, name))
            }
        }
        return result
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
