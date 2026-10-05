package com.remotecontrollan.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.remotecontrollan.model.StreamProfile
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenEncoder(
    private val scope: CoroutineScope,
    private val onEncodedFrame: (ByteArray) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encodingJob: Job? = null
    private var isEncoding = false

    fun prepare(profile: StreamProfile): Surface? {
        stop()

        // Ensure width and height are even numbers (strictly required by H.264/AVC encoders)
        val width = if (profile.width % 2 == 0) profile.width else profile.width - 1
        val height = if (profile.height % 2 == 0) profile.height else profile.height - 1

        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val primaryFormat = createVideoFormat(width, height, profile.fps, profile.bitrateBps, profile.iFrameIntervalSeconds, true)

            try {
                codec.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                AppLogger.w("ScreenEncoder", "High-performance configure failed, falling back to standard baseline AVC: ${e.message}")
                val fallbackFormat = createVideoFormat(width, height, profile.fps, profile.bitrateBps, profile.iFrameIntervalSeconds, false)
                codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            isEncoding = true

            startEncodingLoop()
            AppLogger.i("ScreenEncoder", "MediaCodec started: ${width}x${height} @ ${profile.fps} FPS")
            return inputSurface
        } catch (e: Exception) {
            AppLogger.e("ScreenEncoder", "Failed to start MediaCodec: ${e.message}", e)
            stop()
            return null
        }
    }

    private fun createVideoFormat(width: Int, height: Int, fps: Int, bitrate: Int, iFrameInterval: Int, advancedFlags: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }
        if (advancedFlags) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LATENCY, 0)
                }
            } catch (ignored: Exception) {}
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            } catch (ignored: Exception) {}
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (ignored: Exception) {}
        }
        return format
    }

    private fun startEncodingLoop() {
        encodingJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = mediaCodec ?: return@launch

            while (isActive && isEncoding) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // Read NAL unit / encoded frame bytes
                            val outData = ByteArray(bufferInfo.size)
                            outputBuffer.get(outData)

                            onEncodedFrame(outData)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        AppLogger.i("ScreenEncoder", "Encoder output format changed: ${codec.outputFormat}")
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        AppLogger.w("ScreenEncoder", "Error draining encoder output: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    fun requestKeyFrame() {
        try {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodec?.setParameters(bundle)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop() {
        isEncoding = false
        encodingJob?.cancel()
        encodingJob = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            mediaCodec = null
            inputSurface?.release()
            inputSurface = null
        }
        AppLogger.i("ScreenEncoder", "MediaCodec stopped")
    }
}
