package com.remotecontrollan.network

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

class UsbTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : Transport {

    override val transportType: TransportType = TransportType.USB

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val messageChannel = Channel<ControlMessage>(Channel.BUFFERED)
    override val incomingMessages: Flow<ControlMessage> = messageChannel.receiveAsFlow()

    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
    override val incomingFrames: Flow<ByteArray> = frameChannel.receiveAsFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private var fileInputStream: FileInputStream? = null
    private var fileOutputStream: FileOutputStream? = null

    /**
     * Checks whether direct Android-to-Android USB communication is possible right now.
     */
    fun checkUsbCapability(): UsbCapabilityResult {
        if (usbManager == null) {
            return UsbCapabilityResult(false, "USB Manager unavailable on this system")
        }

        val accessories = usbManager.accessoryList
        val devices = usbManager.deviceList

        if (!accessories.isNullOrEmpty()) {
            return UsbCapabilityResult(true, "USB Accessory detected: ${accessories.first().description}")
        }

        if (devices.isNotEmpty()) {
            // Check for compatible bulk endpoint device
            val matched = devices.values.firstOrNull { d ->
                // Check if device has communication or bulk endpoints
                d.interfaceCount > 0
            }
            if (matched != null) {
                return UsbCapabilityResult(true, "Compatible USB device found: ${matched.deviceName}")
            }
        }

        return UsbCapabilityResult(
            false,
            "Direct USB transport is unavailable on this device configuration. Connect phones via USB-C OTG cable or switch to Wi-Fi Hotspot mode."
        )
    }

    override suspend fun connect(targetAddress: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val capability = checkUsbCapability()
        if (!capability.isSupported) {
            AppLogger.w("UsbTransport", capability.statusMessage)
            return@withContext false
        }

        try {
            val accessories = usbManager?.accessoryList
            if (!accessories.isNullOrEmpty()) {
                val accessory = accessories[0]
                val pfd = usbManager.openAccessory(accessory)
                if (pfd != null) {
                    val fd = pfd.fileDescriptor
                    fileInputStream = FileInputStream(fd)
                    fileOutputStream = FileOutputStream(fd)
                    _isConnected.value = true
                    AppLogger.i("UsbTransport", "USB Accessory connection established")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            AppLogger.e("UsbTransport", "Error initializing USB streams: ${e.message}", e)
        }
        return@withContext false
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            fileInputStream?.close()
            fileOutputStream?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            fileInputStream = null
            fileOutputStream = null
            _isConnected.value = false
        }
    }

    override suspend fun send(message: ControlMessage): Boolean = withContext(Dispatchers.IO) {
        val out = fileOutputStream ?: return@withContext false
        return@withContext try {
            val raw = ProtocolParser.serialize(message).toByteArray(Charsets.UTF_8)
            // 4 bytes length prefix + payload
            val length = raw.size
            out.write(byteArrayOf(
                (length shr 24).toByte(),
                (length shr 16).toByte(),
                (length shr 8).toByte(),
                length.toByte()
            ))
            out.write(raw)
            out.flush()
            true
        } catch (e: Exception) {
            AppLogger.e("UsbTransport", "Failed to send over USB", e)
            false
        }
    }

    override suspend fun sendRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = fileOutputStream ?: return@withContext false
        return@withContext try {
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) {
            AppLogger.e("UsbTransport", "Failed to send raw over USB", e)
            false
        }
    }

    data class UsbCapabilityResult(
        val isSupported: Boolean,
        val statusMessage: String
    )
}
