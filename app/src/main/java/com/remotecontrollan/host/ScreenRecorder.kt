package com.remotecontrollan.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Environment
import android.util.DisplayMetrics
import android.view.WindowManager
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var currentOutputFile: File? = null

    fun startRecording(): Boolean {
        if (_isRecording.value) return true

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(moviesDir, "ScreenRecord_$timestamp.mp4")
            currentOutputFile = file

            val recorder = MediaRecorder()
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoSize(720, 1600)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(4_000_000)
            recorder.setVideoFrameRate(30)
            recorder.prepare()

            val surface = recorder.surface
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RemoteControlLAN-Recorder",
                720,
                1600,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            recorder.start()
            mediaRecorder = recorder
            _isRecording.value = true
            AppLogger.i("ScreenRecorder", "Host screen recording started: ${file.name}")
            return true
        } catch (e: Exception) {
            AppLogger.e("ScreenRecorder", "Failed to start screen recording: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    fun stopRecording(): File? {
        if (!_isRecording.value) return null
        _isRecording.value = false

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            AppLogger.w("ScreenRecorder", "Error stopping recorder", e)
        } finally {
            mediaRecorder = null
            virtualDisplay?.release()
            virtualDisplay = null
        }

        val file = currentOutputFile
        AppLogger.i("ScreenRecorder", "Screen recording saved: ${file?.absolutePath}")
        return file
    }
}
