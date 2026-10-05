package com.remotecontrollan.host

import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

object RootEngine {

    private val PACKAGE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$")
    private var isRootCached: RootStatus? = null

    /**
     * Safely checks for root access without hanging.
     */
    fun checkRootStatus(): RootStatus {
        if (isRootCached != null) return isRootCached!!

        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su"
        )

        var binaryFound = false
        for (path in suPaths) {
            if (File(path).exists()) {
                binaryFound = true
                break
            }
        }

        if (!binaryFound) {
            isRootCached = RootStatus.NOT_DETECTED
            return RootStatus.NOT_DETECTED
        }

        // Test executing su -c id
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            val exitCode = process.waitFor()

            if (exitCode == 0 && line != null && line.contains("uid=0")) {
                AppLogger.i("RootEngine", "Root access confirmed: uid=0")
                isRootCached = RootStatus.DETECTED
                return RootStatus.DETECTED
            }
        } catch (e: Exception) {
            AppLogger.w("RootEngine", "Root check execution failed: ${e.message}")
        } finally {
            process?.destroy()
        }

        isRootCached = RootStatus.NOT_DETECTED
        return RootStatus.NOT_DETECTED
    }

    /**
     * Executes input tap <x> <y> via controlled su.
     */
    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        if (x < 0 || y < 0 || x > 10000 || y > 10000) return@withContext false
        val cmd = "input tap ${x.toInt()} ${y.toInt()}"
        executeAllowlistedCommand(cmd)
    }

    /**
     * Executes input swipe <x1> <y1> <x2> <y2> <duration> via controlled su.
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val clampedDuration = durationMs.coerceIn(50, 5000)
        val cmd = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $clampedDuration"
        executeAllowlistedCommand(cmd)
    }

    /**
     * Executes input keyevent <codeValue> via controlled su.
     */
    suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        if (keyCode !in 0..300) return@withContext false
        val cmd = "input keyevent $keyCode"
        executeAllowlistedCommand(cmd)
    }

    /**
     * Sends text input via controlled su.
     * Escapes spaces and special characters to prevent shell injection.
     */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isEmpty() || text.length > 500) return@withContext false
        // Shell escape for Android input text: replace spaces with %s, escape quotes
        val escaped = text.replace(" ", "%s")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace(";", "\\;")
            .replace("|", "\\|")
            .replace("$", "\\$")
            .replace("`", "\\`")

        val cmd = "input text \"$escaped\""
        executeAllowlistedCommand(cmd)
    }

    /**
     * Launches an application package.
     */
    suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
            AppLogger.w("RootEngine", "Rejected invalid package name: $packageName")
            return@withContext false
        }
        val cmd = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        executeAllowlistedCommand(cmd)
    }

    /**
     * Forces stop on a package.
     */
    suspend fun stopPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
            AppLogger.w("RootEngine", "Rejected invalid package name: $packageName")
            return@withContext false
        }
        val cmd = "am force-stop $packageName"
        executeAllowlistedCommand(cmd)
    }

    /**
     * Takes screenshot to a specific path using screencap.
     */
    suspend fun takeScreenshot(destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        // Prevent path traversal
        if (destinationPath.contains("..") || !destinationPath.startsWith("/data/user") && !destinationPath.startsWith("/sdcard") && !destinationPath.startsWith("/data/data")) {
            return@withContext false
        }
        val cmd = "screencap -p \"$destinationPath\""
        executeAllowlistedCommand(cmd)
    }

    /**
     * Strictly executes only verified allowlisted commands via su.
     */
    private fun executeAllowlistedCommand(command: String): Boolean {
        // Strict allowlist validation: only allow commands starting with input, monkey, am force-stop, screencap
        val allowedPrefixes = listOf("input tap", "input swipe", "input keyevent", "input text", "monkey -p", "am force-stop", "screencap -p")
        val isAllowed = allowedPrefixes.any { command.startsWith(it) }
        if (!isAllowed) {
            AppLogger.e("RootEngine", "Refusing to execute unallowlisted command: $command")
            return false
        }

        var process: Process? = null
        var os: DataOutputStream? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            AppLogger.e("RootEngine", "Execution failed for: $command", e)
            false
        } finally {
            try { os?.close() } catch (ignored: Exception) {}
            process?.destroy()
        }
    }
}
