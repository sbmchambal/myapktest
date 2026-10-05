package com.remotecontrollan.host

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.utils.AppLogger

class InputEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownTime = 0L

    suspend fun handleTap(x: Float, y: Float): Boolean {
        if (isRooted()) {
            return RootEngine.tap(x, y)
        }
        val acc = AccessibilityInputService.getInstance()
        if (acc != null) {
            return acc.injectTap(x, y)
        }
        AppLogger.w("InputEngine", "Cannot handle tap: No root and AccessibilityService inactive")
        return false
    }

    suspend fun handleTouch(action: String, x: Float, y: Float): Boolean {
        when (action) {
            "down" -> {
                lastTouchX = x
                lastTouchY = y
                touchDownTime = System.currentTimeMillis()
                return true
            }
            "move" -> {
                // If moving significantly, prepare for swipe or drag
                return true
            }
            "up" -> {
                val duration = System.currentTimeMillis() - touchDownTime
                val dx = kotlin.math.abs(x - lastTouchX)
                val dy = kotlin.math.abs(y - lastTouchY)
                return if (dx > 20 || dy > 20) {
                    handleSwipe(lastTouchX, lastTouchY, x, y, duration.coerceIn(100, 1000))
                } else {
                    handleTap(x, y)
                }
            }
        }
        return false
    }

    suspend fun handleSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (isRooted()) {
            return RootEngine.swipe(x1, y1, x2, y2, durationMs)
        }
        val acc = AccessibilityInputService.getInstance()
        if (acc != null) {
            return acc.injectSwipe(x1, y1, x2, y2, durationMs)
        }
        return false
    }

    suspend fun handleKey(keyCode: Int): Boolean {
        if (isRooted()) {
            return RootEngine.keyEvent(keyCode)
        }
        // Handle common keys
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return handleBack()
            KeyEvent.KEYCODE_HOME -> return handleHome()
            KeyEvent.KEYCODE_APP_SWITCH -> return handleRecent()
            KeyEvent.KEYCODE_VOLUME_UP -> return handleVolumeUp()
            KeyEvent.KEYCODE_VOLUME_DOWN -> return handleVolumeDown()
        }
        return false
    }

    suspend fun handleText(text: String): Boolean {
        if (isRooted()) {
            return RootEngine.inputText(text)
        }
        AppLogger.w("InputEngine", "Direct text injection requires root or custom IME")
        return false
    }

    suspend fun handleBack(): Boolean {
        if (isRooted()) return RootEngine.keyEvent(KeyEvent.KEYCODE_BACK)
        return AccessibilityInputService.getInstance()?.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }

    suspend fun handleHome(): Boolean {
        if (isRooted()) return RootEngine.keyEvent(KeyEvent.KEYCODE_HOME)
        return AccessibilityInputService.getInstance()?.performGlobal(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    suspend fun handleRecent(): Boolean {
        if (isRooted()) return RootEngine.keyEvent(KeyEvent.KEYCODE_APP_SWITCH)
        return AccessibilityInputService.getInstance()?.performGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }

    fun handleVolumeUp(): Boolean {
        audioManager?.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return true
    }

    fun handleVolumeDown(): Boolean {
        audioManager?.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return true
    }

    suspend fun handlePower(): Boolean {
        if (isRooted()) return RootEngine.keyEvent(KeyEvent.KEYCODE_POWER)
        return AccessibilityInputService.getInstance()?.performGlobal(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: false
    }

    private fun isRooted(): Boolean = RootEngine.checkRootStatus() == RootStatus.DETECTED
}
