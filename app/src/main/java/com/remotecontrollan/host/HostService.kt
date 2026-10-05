package com.remotecontrollan.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.remotecontrollan.MainActivity
import com.remotecontrollan.R
import com.remotecontrollan.model.DeviceInfo
import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.model.StreamProfile
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.network.HostDiscoveryBroadcaster
import com.remotecontrollan.pairing.PairingManager
import com.remotecontrollan.utils.AppLogger
import com.remotecontrollan.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HostService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): HostService = this@HostService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var pairingManager: PairingManager
    private lateinit var inputEngine: InputEngine
    private lateinit var fileManager: FileManager
    private lateinit var appManager: AppManager
    private lateinit var clipboardSync: ClipboardSync
    private lateinit var hostServer: HostServer

    private var screenEncoder: ScreenEncoder? = null
    private var screenCapture: ScreenCapture? = null
    private var screenRecorder: ScreenRecorder? = null
    private var discoveryBroadcaster: HostDiscoveryBroadcaster? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val _isHostRunning = MutableStateFlow(false)
    val isHostRunning: StateFlow<Boolean> = _isHostRunning.asStateFlow()

    private val _hostDeviceInfo = MutableStateFlow(DeviceInfo())
    val hostDeviceInfo: StateFlow<DeviceInfo> = _hostDeviceInfo.asStateFlow()

    private val _fpsStatus = MutableStateFlow(0)
    val fpsStatus: StateFlow<Int> = _fpsStatus.asStateFlow()

    private val _currentPin = MutableStateFlow("")
    val currentPin: StateFlow<String> = _currentPin.asStateFlow()

    private val _connectedControllers = MutableStateFlow(0)
    val connectedControllers: StateFlow<Int> = _connectedControllers.asStateFlow()

    companion object {
        const val ACTION_START = "ACTION_START_HOST"
        const val ACTION_STOP = "ACTION_STOP_HOST"
        const val ACTION_DISCONNECT_ALL = "ACTION_DISCONNECT_ALL"
        const val NOTIFICATION_CHANNEL_ID = "remote_control_lan_channel"
        const val NOTIFICATION_ID = 1001

        private var activeServiceInstance: HostService? = null
        fun getInstance(): HostService? = activeServiceInstance
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()

        pairingManager = PairingManager(this)
        inputEngine = InputEngine(this)
        fileManager = FileManager(this)
        appManager = AppManager(this)
        clipboardSync = ClipboardSync(this)

        hostServer = HostServer(
            context = this,
            scope = serviceScope,
            pairingManager = pairingManager,
            inputEngine = inputEngine,
            fileManager = fileManager,
            appManager = appManager,
            clipboardSync = clipboardSync
        )

        serviceScope.launch {
            pairingManager.currentPin.collect {
                _currentPin.value = it
            }
        }

        serviceScope.launch {
            hostServer.connectedControllersCount.collect { count ->
                _connectedControllers.value = count
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", -1)
                val data = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("EXTRA_DATA", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>("EXTRA_DATA")
                    }
                } catch (e: Exception) {
                    AppLogger.e("HostService", "Failed to extract EXTRA_DATA: ${e.message}", e)
                    null
                }

                if (resultCode != -1 && data != null) {
                    // Start foreground service first before obtaining MediaProjection (mandated on Android 14+ / SDK 35)
                    val fgSuccess = startForegroundServiceWithNotification()
                    if (fgSuccess) {
                        startHostSubsystems(resultCode, data)
                    } else {
                        AppLogger.e("HostService", "Failed to start foreground service. Aborting host start.")
                        stopSelf()
                    }
                } else {
                    AppLogger.e("HostService", "Invalid resultCode ($resultCode) or null intent data for ACTION_START")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopHost()
                stopSelf()
            }
            ACTION_DISCONNECT_ALL -> {
                hostServer.disconnectAll()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification(): Boolean {
        return try {
            val notification = buildNotification("Host Active - Waiting for controller")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use ONLY ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLogger.i("HostService", "Foreground service started with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
            true
        } catch (e: Exception) {
            AppLogger.e("HostService", "Failed to start foreground service: ${e.message}", e)
            false
        }
    }

    private fun startHostSubsystems(resultCode: Int, data: Intent) {
        val mpManager = try {
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        } catch (e: Exception) {
            AppLogger.e("HostService", "Failed to get MediaProjectionManager: ${e.message}", e)
            null
        }

        if (mpManager == null) {
            AppLogger.e("HostService", "MediaProjectionManager is unavailable on this device")
            stopHost()
            stopSelf()
            return
        }

        val mp = try {
            mpManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            AppLogger.e("HostService", "Exception while calling getMediaProjection(): ${e.message}", e)
            null
        }

        if (mp == null) {
            AppLogger.e("HostService", "getMediaProjection() returned null - screen capture consent revoked or unavailable")
            stopHost()
            stopSelf()
            return
        }
        mediaProjection = mp

        // Register MediaProjection.Callback (Mandatory on Android 14+ / targetSdk 35 before VirtualDisplay)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                AppLogger.i("HostService", "MediaProjection session terminated by system or user")
                stopHost()
                stopSelf()
            }
        }

        try {
            mp.registerCallback(callback, Handler(Looper.getMainLooper()))
            projectionCallback = callback
            AppLogger.i("HostService", "MediaProjection.Callback registered successfully")
        } catch (e: Exception) {
            AppLogger.e("HostService", "Failed to register MediaProjection.Callback: ${e.message}", e)
            try { mp.stop() } catch (ignored: Exception) {}
            mediaProjection = null
            stopHost()
            stopSelf()
            return
        }

        // Screen encoder
        val encoder = ScreenEncoder(serviceScope) { frameBytes ->
            hostServer.sendEncodedFrame(frameBytes)
        }
        screenEncoder = encoder

        val streamProfile = StreamProfile()
        val surface = try {
            encoder.prepare(streamProfile)
        } catch (e: Exception) {
            AppLogger.e("HostService", "ScreenEncoder initialization failed: ${e.message}", e)
            null
        }

        if (surface == null) {
            AppLogger.e("HostService", "Failed to prepare encoder surface for screen capture")
            stopHost()
            stopSelf()
            return
        }

        val capture = ScreenCapture(this, mp)
        val captureSuccess = try {
            capture.start(surface, streamProfile)
        } catch (e: Exception) {
            AppLogger.e("HostService", "ScreenCapture start failed: ${e.message}", e)
            false
        }

        if (!captureSuccess) {
            AppLogger.e("HostService", "Failed to create VirtualDisplay for screen capture")
            stopHost()
            stopSelf()
            return
        }
        screenCapture = capture

        // Screen recorder (optional)
        try {
            screenRecorder = ScreenRecorder(this, mp)
        } catch (e: Exception) {
            AppLogger.w("HostService", "Optional ScreenRecorder initialization skipped: ${e.message}")
        }

        // Start embedded server
        try {
            hostServer.start()
        } catch (e: Exception) {
            AppLogger.e("HostService", "Failed to start HostServer: ${e.message}", e)
            stopHost()
            stopSelf()
            return
        }

        // Refresh device info
        val rootStatus = RootEngine.checkRootStatus()
        val info = DeviceInfo(
            deviceName = Build.MANUFACTURER + " " + Build.MODEL,
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            rootStatus = rootStatus,
            ipAddress = NetworkUtils.getLocalIpAddress(),
            port = NetworkUtils.DEFAULT_WEBSOCKET_PORT,
            isHotspotActive = NetworkUtils.isHotspotInterfaceActive(),
            transport = TransportType.WIFI
        )
        _hostDeviceInfo.value = info

        // Start UDP discovery broadcaster
        try {
            discoveryBroadcaster = HostDiscoveryBroadcaster(
                scope = serviceScope,
                hostId = pairingManager.generateQrPayload(info.ipAddress, info.port, info.deviceName),
                deviceName = info.deviceName,
                model = info.model,
                isRooted = rootStatus == RootStatus.DETECTED,
                port = info.port
            )
            discoveryBroadcaster?.start()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Discovery broadcaster failed to start: ${e.message}")
        }

        _isHostRunning.value = true
        AppLogger.i("HostService", "HostService fully active on ${info.ipAddress}:${info.port}")
    }

    fun stopHost() {
        try {
            discoveryBroadcaster?.stop()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping discovery broadcaster: ${e.message}")
        }
        discoveryBroadcaster = null

        try {
            screenCapture?.stop()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping screen capture: ${e.message}")
        }
        screenCapture = null

        try {
            screenEncoder?.stop()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping screen encoder: ${e.message}")
        }
        screenEncoder = null

        try {
            screenRecorder?.stopRecording()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping screen recorder: ${e.message}")
        }
        screenRecorder = null

        try {
            projectionCallback?.let {
                mediaProjection?.unregisterCallback(it)
            }
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error unregistering projection callback: ${e.message}")
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping MediaProjection: ${e.message}")
        }
        mediaProjection = null

        try {
            hostServer.stop()
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping HostServer: ${e.message}")
        }

        _isHostRunning.value = false
        AppLogger.i("HostService", "HostService stopped cleanly")

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            AppLogger.w("HostService", "Error stopping foreground service: ${e.message}")
        }
    }

    fun regeneratePairingPin(): String {
        return pairingManager.regeneratePin()
    }

    fun getQrPayload(): String {
        val info = _hostDeviceInfo.value
        return pairingManager.generateQrPayload(info.ipAddress, info.port, info.deviceName)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenApp = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, HostService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val disconnectIntent = Intent(this, HostService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val pDisconnect = PendingIntent.getService(this, 2, disconnectIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RemoteControl LAN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pOpenApp)
            .addAction(R.drawable.ic_launcher_foreground, "Disconnect", pDisconnect)
            .addAction(R.drawable.ic_launcher_foreground, "Stop Host", pStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification() {
        val count = _connectedControllers.value
        val text = if (count > 0) "Host active: $count controller(s) connected" else "Host active - Ready for connection"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RemoteControl LAN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active host status and control actions"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopHost()
        serviceScope.cancel()
        activeServiceInstance = null
    }
}
