package com.remotecontrollan.controller

import android.graphics.RectF
import com.remotecontrollan.network.ControlMessage
import kotlin.math.max
import kotlin.math.min

class TouchController(
    private var hostWidth: Int = 1080,
    private var hostHeight: Int = 2400,
    private val onSendCommand: (ControlMessage) -> Unit
) {
    private var viewWidth: Float = 1f
    private var viewHeight: Float = 1f
    private val contentRect = RectF()

    fun updateDimensions(viewW: Float, viewH: Float, hostW: Int, hostH: Int) {
        this.viewWidth = max(1f, viewW)
        this.viewHeight = max(1f, viewH)
        this.hostWidth = max(1, hostW)
        this.hostHeight = max(1, hostH)

        // Calculate aspect ratio fit (letterboxing / pillarboxing)
        val hostAspect = hostW.toFloat() / hostH.toFloat()
        val viewAspect = viewW / viewH

        if (viewAspect > hostAspect) {
            // Letterbox horizontally
            val targetW = viewH * hostAspect
            val left = (viewW - targetW) / 2f
            contentRect.set(left, 0f, left + targetW, viewH)
        } else {
            // Letterbox vertically
            val targetH = viewW / hostAspect
            val top = (viewH - targetH) / 2f
            contentRect.set(0f, top, viewW, top + targetH)
        }
    }

    /**
     * Maps local view coordinates (px) to Host's physical coordinate space [0, hostWidth] x [0, hostHeight]
     */
    fun mapToHost(localX: Float, localY: Float): Pair<Float, Float>? {
        if (!contentRect.contains(localX, localY)) {
            // Click outside remote screen bounds (letterbox padding)
            return null
        }
        val normalizedX = (localX - contentRect.left) / contentRect.width()
        val normalizedY = (localY - contentRect.top) / contentRect.height()

        val hostX = (normalizedX * hostWidth).coerceIn(0f, hostWidth.toFloat())
        val hostY = (normalizedY * hostHeight).coerceIn(0f, hostHeight.toFloat())
        return Pair(hostX, hostY)
    }

    fun onTouchDown(localX: Float, localY: Float, pointerId: Int = 0) {
        val (hostX, hostY) = mapToHost(localX, localY) ?: return
        onSendCommand(
            ControlMessage.Touch(
                action = "down",
                pointerId = pointerId,
                x = hostX,
                y = hostY,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun onTouchMove(localX: Float, localY: Float, pointerId: Int = 0) {
        val (hostX, hostY) = mapToHost(localX, localY) ?: return
        onSendCommand(
            ControlMessage.Touch(
                action = "move",
                pointerId = pointerId,
                x = hostX,
                y = hostY,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun onTouchUp(localX: Float, localY: Float, pointerId: Int = 0) {
        val mapped = mapToHost(localX, localY)
        val hostX = mapped?.first ?: ((localX / viewWidth) * hostWidth).coerceIn(0f, hostWidth.toFloat())
        val hostY = mapped?.second ?: ((localY / viewHeight) * hostHeight).coerceIn(0f, hostHeight.toFloat())

        onSendCommand(
            ControlMessage.Touch(
                action = "up",
                pointerId = pointerId,
                x = hostX,
                y = hostY,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun onTap(localX: Float, localY: Float) {
        val (hostX, hostY) = mapToHost(localX, localY) ?: return
        onSendCommand(ControlMessage.Tap(x = hostX, y = hostY))
    }

    fun onLongPress(localX: Float, localY: Float) {
        val (hostX, hostY) = mapToHost(localX, localY) ?: return
        onSendCommand(ControlMessage.LongPress(x = hostX, y = hostY, durationMs = 600))
    }

    fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val startMapped = mapToHost(startX, startY) ?: return
        val endMapped = mapToHost(endX, endY) ?: return

        onSendCommand(
            ControlMessage.Swipe(
                x1 = startMapped.first,
                y1 = startMapped.second,
                x2 = endMapped.first,
                y2 = endMapped.second,
                durationMs = durationMs
            )
        )
    }
}
