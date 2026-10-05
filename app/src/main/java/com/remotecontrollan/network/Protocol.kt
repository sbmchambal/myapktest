package com.remotecontrollan.network

import org.json.JSONObject

/**
 * Protocol command model and JSON serializer/deserializer.
 */
sealed class ControlMessage {
    data class Auth(val controllerId: String, val token: String, val deviceName: String) : ControlMessage()
    data class AuthResponse(val success: Boolean, val message: String) : ControlMessage()
    data class PairingRequest(val controllerId: String, val deviceName: String, val pin: String) : ControlMessage()
    data class PairingResponse(val success: Boolean, val token: String?, val message: String) : ControlMessage()

    data class Touch(
        val action: String, // "down", "move", "up", "cancel"
        val pointerId: Int = 0,
        val x: Float,
        val y: Float,
        val timestamp: Long = System.currentTimeMillis()
    ) : ControlMessage()

    data class Tap(val x: Float, val y: Float) : ControlMessage()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long) : ControlMessage()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long = 500) : ControlMessage()

    data class Key(val keyCode: Int) : ControlMessage()
    data class Text(val text: String) : ControlMessage()

    // Navigation shortcuts
    object Back : ControlMessage()
    object Home : ControlMessage()
    object Recent : ControlMessage()
    object VolumeUp : ControlMessage()
    object VolumeDown : ControlMessage()
    object Power : ControlMessage()

    // System commands
    object Screenshot : ControlMessage()
    data class LaunchApp(val packageName: String) : ControlMessage()
    data class CloseApp(val packageName: String) : ControlMessage()

    // Clipboard
    data class ClipboardSend(val text: String) : ControlMessage()
    object ClipboardRequest : ControlMessage()
    data class ClipboardResponse(val text: String) : ControlMessage()

    // Stream control & metadata
    data class ScreenMetadata(val width: Int, val height: Int, val orientation: Int) : ControlMessage()
    data class QualityChange(val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) : ControlMessage()

    // Ping / Pong
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : ControlMessage()
    data class Pong(val timestamp: Long) : ControlMessage()
}

object ProtocolParser {

    fun serialize(msg: ControlMessage): String {
        val json = JSONObject()
        when (msg) {
            is ControlMessage.Auth -> {
                json.put("type", "auth")
                json.put("controllerId", msg.controllerId)
                json.put("token", msg.token)
                json.put("deviceName", msg.deviceName)
            }
            is ControlMessage.AuthResponse -> {
                json.put("type", "auth_response")
                json.put("success", msg.success)
                json.put("message", msg.message)
            }
            is ControlMessage.PairingRequest -> {
                json.put("type", "pairing_request")
                json.put("controllerId", msg.controllerId)
                json.put("deviceName", msg.deviceName)
                json.put("pin", msg.pin)
            }
            is ControlMessage.PairingResponse -> {
                json.put("type", "pairing_response")
                json.put("success", msg.success)
                json.put("token", msg.token ?: "")
                json.put("message", msg.message)
            }
            is ControlMessage.Touch -> {
                json.put("type", "touch")
                json.put("action", msg.action)
                json.put("pointerId", msg.pointerId)
                json.put("x", msg.x.toDouble())
                json.put("y", msg.y.toDouble())
                json.put("timestamp", msg.timestamp)
            }
            is ControlMessage.Tap -> {
                json.put("type", "tap")
                json.put("x", msg.x.toDouble())
                json.put("y", msg.y.toDouble())
            }
            is ControlMessage.Swipe -> {
                json.put("type", "swipe")
                json.put("x1", msg.x1.toDouble())
                json.put("y1", msg.y1.toDouble())
                json.put("x2", msg.x2.toDouble())
                json.put("y2", msg.y2.toDouble())
                json.put("duration", msg.durationMs)
            }
            is ControlMessage.LongPress -> {
                json.put("type", "long_press")
                json.put("x", msg.x.toDouble())
                json.put("y", msg.y.toDouble())
                json.put("duration", msg.durationMs)
            }
            is ControlMessage.Key -> {
                json.put("type", "key")
                json.put("keyCode", msg.keyCode)
            }
            is ControlMessage.Text -> {
                json.put("type", "text")
                json.put("text", msg.text)
            }
            is ControlMessage.Back -> json.put("type", "back")
            is ControlMessage.Home -> json.put("type", "home")
            is ControlMessage.Recent -> json.put("type", "recent")
            is ControlMessage.VolumeUp -> json.put("type", "volume_up")
            is ControlMessage.VolumeDown -> json.put("type", "volume_down")
            is ControlMessage.Power -> json.put("type", "power")
            is ControlMessage.Screenshot -> json.put("type", "screenshot")
            is ControlMessage.LaunchApp -> {
                json.put("type", "launch_app")
                json.put("packageName", msg.packageName)
            }
            is ControlMessage.CloseApp -> {
                json.put("type", "close_app")
                json.put("packageName", msg.packageName)
            }
            is ControlMessage.ClipboardSend -> {
                json.put("type", "clipboard_send")
                json.put("text", msg.text)
            }
            is ControlMessage.ClipboardRequest -> json.put("type", "clipboard_request")
            is ControlMessage.ClipboardResponse -> {
                json.put("type", "clipboard_response")
                json.put("text", msg.text)
            }
            is ControlMessage.ScreenMetadata -> {
                json.put("type", "screen_metadata")
                json.put("width", msg.width)
                json.put("height", msg.height)
                json.put("orientation", msg.orientation)
            }
            is ControlMessage.QualityChange -> {
                json.put("type", "quality_change")
                json.put("width", msg.width)
                json.put("height", msg.height)
                json.put("fps", msg.fps)
                json.put("bitrate", msg.bitrateBps)
            }
            is ControlMessage.Ping -> {
                json.put("type", "ping")
                json.put("timestamp", msg.timestamp)
            }
            is ControlMessage.Pong -> {
                json.put("type", "pong")
                json.put("timestamp", msg.timestamp)
            }
        }
        return json.toString()
    }

    fun parse(raw: String): ControlMessage? {
        return try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "auth" -> ControlMessage.Auth(
                    json.getString("controllerId"),
                    json.getString("token"),
                    json.optString("deviceName", "Remote Controller")
                )
                "auth_response" -> ControlMessage.AuthResponse(
                    json.getBoolean("success"),
                    json.optString("message", "")
                )
                "pairing_request" -> ControlMessage.PairingRequest(
                    json.getString("controllerId"),
                    json.optString("deviceName", "Controller"),
                    json.getString("pin")
                )
                "pairing_response" -> ControlMessage.PairingResponse(
                    json.getBoolean("success"),
                    json.optString("token", null),
                    json.optString("message", "")
                )
                "touch" -> ControlMessage.Touch(
                    action = json.getString("action"),
                    pointerId = json.optInt("pointerId", 0),
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                "tap" -> ControlMessage.Tap(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat()
                )
                "swipe" -> ControlMessage.Swipe(
                    x1 = json.getDouble("x1").toFloat(),
                    y1 = json.getDouble("y1").toFloat(),
                    x2 = json.getDouble("x2").toFloat(),
                    y2 = json.getDouble("y2").toFloat(),
                    durationMs = json.optLong("duration", 300)
                )
                "long_press" -> ControlMessage.LongPress(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    durationMs = json.optLong("duration", 500)
                )
                "key" -> ControlMessage.Key(json.getInt("keyCode"))
                "text" -> ControlMessage.Text(json.getString("text"))
                "back" -> ControlMessage.Back
                "home" -> ControlMessage.Home
                "recent" -> ControlMessage.Recent
                "volume_up" -> ControlMessage.VolumeUp
                "volume_down" -> ControlMessage.VolumeDown
                "power" -> ControlMessage.Power
                "screenshot" -> ControlMessage.Screenshot
                "launch_app" -> ControlMessage.LaunchApp(json.getString("packageName"))
                "close_app" -> ControlMessage.CloseApp(json.getString("packageName"))
                "clipboard_send" -> ControlMessage.ClipboardSend(json.getString("text"))
                "clipboard_request" -> ControlMessage.ClipboardRequest
                "clipboard_response" -> ControlMessage.ClipboardResponse(json.optString("text", ""))
                "screen_metadata" -> ControlMessage.ScreenMetadata(
                    json.getInt("width"),
                    json.getInt("height"),
                    json.optInt("orientation", 0)
                )
                "quality_change" -> ControlMessage.QualityChange(
                    json.getInt("width"),
                    json.getInt("height"),
                    json.getInt("fps"),
                    json.getInt("bitrate")
                )
                "ping" -> ControlMessage.Ping(json.optLong("timestamp", System.currentTimeMillis()))
                "pong" -> ControlMessage.Pong(json.optLong("timestamp", System.currentTimeMillis()))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
