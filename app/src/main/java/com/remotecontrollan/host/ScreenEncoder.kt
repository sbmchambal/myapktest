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

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                profile.width,
                profile.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.iFrameIntervalSeconds)
                // Low latency flags
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (e: Exception) {
                    // ignore if CBR unsupported
                }
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            isEncoding = true

            startEncodingLoop()
            AppLogger.i("ScreenEncoder", "MediaCodec started: ${profile.width}x${profile.height} @ ${profile.fps} FPS")
            return inputSurface
        } catch (e: Exception) {
            AppLogger.e("ScreenEncoder", "Failed to start MediaCodec: ${e.message}", e)
            stop()
            return null
        }
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
