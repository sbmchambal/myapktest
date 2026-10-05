package com.remotecontrollan.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.remotecontrollan.model.StreamProfile
import com.remotecontrollan.utils.AppLogger

class ScreenCapture(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private var virtualDisplay: VirtualDisplay? = null

    fun start(surface: Surface, profile: StreamProfile): Boolean {
        stop()

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_HIGH

        return try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RemoteControlLAN-Display",
                profile.width,
                profile.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            val success = virtualDisplay != null
            if (success) {
                AppLogger.i("ScreenCapture", "VirtualDisplay created: ${profile.width}x${profile.height} @ ${densityDpi}dpi")
            } else {
                AppLogger.e("ScreenCapture", "createVirtualDisplay returned null")
            }
            success
        } catch (e: Exception) {
            AppLogger.e("ScreenCapture", "Failed to create VirtualDisplay: ${e.message}", e)
            false
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            virtualDisplay = null
        }
    }
}
