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

    fun start(surface: Surface, profile: StreamProfile) {
        stop()

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RemoteControlLAN-Display",
                profile.width,
                profile.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                surface,
                null,
                null
            )
            AppLogger.i("ScreenCapture", "VirtualDisplay created: ${profile.width}x${profile.height}")
        } catch (e: Exception) {
            AppLogger.e("ScreenCapture", "Failed to create VirtualDisplay: ${e.message}", e)
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
