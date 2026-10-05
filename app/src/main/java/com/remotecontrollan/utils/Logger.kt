package com.remotecontrollan.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val TAG = "RemoteControlLAN"
    private const val MAX_ENTRIES = 500

    data class LogItem(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return "[$date] [$level/$tag] $message"
        }
    }

    private val logs = CopyOnWriteArrayList<LogItem>()

    fun d(tag: String, message: String) {
        val sanitized = sanitize(message)
        Log.d(tag, sanitized)
        record("DEBUG", tag, sanitized)
    }

    fun i(tag: String, message: String) {
        val sanitized = sanitize(message)
        Log.i(tag, sanitized)
        record("INFO", tag, sanitized)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        Log.w(tag, sanitized, throwable)
        record("WARN", tag, if (throwable != null) "$sanitized (${throwable.message})" else sanitized)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        Log.e(tag, sanitized, throwable)
        record("ERROR", tag, if (throwable != null) "$sanitized (${throwable.message})" else sanitized)
    }

    private fun record(level: String, tag: String, message: String) {
        logs.add(LogItem(System.currentTimeMillis(), level, tag, message))
        if (logs.size > MAX_ENTRIES) {
            logs.removeAt(0)
        }
    }

    fun getLogs(): List<LogItem> = logs.toList()

    fun exportLogString(): String {
        val sb = StringBuilder()
        sb.append("=== RemoteControl LAN Diagnostic Log ===\n")
        sb.append("Generated: ").append(Date().toString()).append("\n")
        sb.append("----------------------------------------\n")
        for (item in logs) {
            sb.append(item.format()).append("\n")
        }
        return sb.toString()
    }

    fun clear() {
        logs.clear()
    }

    /**
     * Strict sanitizer: never logs pairing tokens, PINs, or raw passwords.
     */
    private fun sanitize(input: String): String {
        return input
            .replace(Regex("(?i)token['\":\\s=]+[a-zA-Z0-9_-]{10,}"), "token=***REDACTED***")
            .replace(Regex("(?i)pin['\":\\s=]+\\d{6}"), "pin=***REDACTED***")
            .replace(Regex("(?i)password['\":\\s=]+[^\\s,;\"']+"), "password=***REDACTED***")
    }
}
