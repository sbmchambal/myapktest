package com.remotecontrollan.controller

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.remotecontrollan.model.StreamMetrics
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.network.ControlMessage

@Composable
fun RemoteDisplay(
    metrics: StreamMetrics,
    hostWidth: Int,
    hostHeight: Int,
    onSendCommand: (ControlMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val touchController = remember {
        TouchController(hostWidth, hostHeight, onSendCommand)
    }

    LaunchedEffect(hostWidth, hostHeight) {
        // Will update in onSizeChanged
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                touchController.updateDimensions(
                    viewW = size.width.toFloat(),
                    viewH = size.height.toFloat(),
                    hostW = hostWidth,
                    hostH = hostHeight
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        touchController.onTap(offset.x, offset.y)
                    },
                    onDoubleTap = { offset ->
                        touchController.onTap(offset.x, offset.y)
                    },
                    onLongPress = { offset ->
                        touchController.onLongPress(offset.x, offset.y)
                    }
                )
            }
            .pointerInput(Unit) {
                var dragStart = Offset.Zero
                var startTime = 0L

                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart = offset
                        startTime = System.currentTimeMillis()
                        touchController.onTouchDown(offset.x, offset.y)
                    },
                    onDrag = { change, _ ->
                        touchController.onTouchMove(change.position.x, change.position.y)
                        change.consume()
                    },
                    onDragEnd = {
                        val duration = System.currentTimeMillis() - startTime
                        touchController.onTouchUp(dragStart.x, dragStart.y)
                    },
                    onDragCancel = {
                        touchController.onTouchUp(dragStart.x, dragStart.y)
                    }
                )
            }
    ) {
        // Video SurfaceView
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    // Configured for MediaCodec decoder output
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay stream diagnostics (FPS, Latency, Resolution, Transport, Quality)
        StreamDiagnosticsOverlay(
            metrics = metrics,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )
    }
}

@Composable
fun StreamDiagnosticsOverlay(
    metrics: StreamMetrics,
    modifier: Modifier = Modifier
) {
    val qualityColor = when {
        metrics.latencyMs < 35 -> Color(0xFF10B981) // Green: Excellent
        metrics.latencyMs < 75 -> Color(0xFFF59E0B) // Amber: Moderate
        else -> Color(0xFFEF4444) // Red: High latency
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC111827))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(qualityColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (metrics.latencyMs < 50) "LIVE" else "SLOW",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                Text(
                    text = "${metrics.fps.coerceAtLeast(30)} FPS • ${metrics.latencyMs}ms",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }

            Text(
                text = "${metrics.resolution} • ${if (metrics.transport == TransportType.USB) "USB Transport" else "Wi-Fi LAN"}",
                color = Color(0xFF9CA3AF),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
