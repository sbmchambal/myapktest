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

    private val _hostError = MutableStateFlow<String?>(null)
    val hostError: StateFlow<String?> = _hostError.asStateFlow()

    fun setFailureState(error: String) {
        _hostError.value = error
        AppLogger.e("Host", "[Host] Failure: $error")
    }

    fun clearFailureState() {
        _hostError.value = null
    }

    companion object {
        const val ACTION_START = "ACTION_START_HOST"
        const val ACTION_STOP = "ACTION_STOP_HOST"
        const val ACTION_DISCONNECT_ALL = "ACTION_DISCONNECT_ALL"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"

        const val NOTIFICATION_CHANNEL_ID = "remote_control_lan_channel"
        const val NOTIFICATION_ID = 1001

        private var activeServiceInstance: HostService? = null
        fun getInstance(): HostService? = activeServiceInstance

        private var pendingResultCode: Int = -1
        private var pendingResultData: Intent? = null

        fun setPendingProjectionData(resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingResultData = data
        }

        fun clearPendingProjectionData() {
            pendingResultCode = -1
            pendingResultData = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this

        // 1. Create notification channel
        createNotificationChannel()

        // 2. Create the foreground notification
        val notification = buildNotification("Host Active - Screen streaming service")

        // 3. Promote service to foreground IMMEDIATELY to prevent ForegroundServiceDidNotStartInTimeException
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }
            AppLogger.i("Host", "[Host] service created")
            AppLogger.i("Host", "[Host] startForeground complete")
        } catch (e: Exception) {
            AppLogger.e("Host", "[Host] Failed calling startForeground in onCreate: ${e.message}", e)
        }

        // 4. Initialize local manager subsystems after entering foreground
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
                // The service is already running as a foreground service at this point.
                var resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                var projectionData = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                            ?: intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        (intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                            ?: intent.getParcelableExtra<Intent>(EXTRA_DATA))
                    }
                } catch (e: Exception) {
                    AppLogger.w("Host", "Failed to extract projection data from intent: ${e.message}")
                    null
                }

                if (resultCode == -1 || projectionData == null) {
                    if (pendingResultCode != -1 && pendingResultData != null) {
                        resultCode = pendingResultCode
                        projectionData = pendingResultData
                        AppLogger.i("Host", "Retrieved projection credentials from pending memory handoff")
                    }
                }

                if (resultCode == -1 || projectionData == null) {
                    AppLogger.e("Host", "Invalid resultCode ($resultCode) or null intent data for ACTION_START")
                    setFailureState("Screen recording permission data was not received")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Start MediaProjection and the remaining Host subsystems asynchronously here
                serviceScope.launch(Dispatchers.Main) {
                    startHostSubsystems(resultCode, projectionData)
                }

                return START_STICKY
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

    private fun startHostSubsystems(resultCode: Int, data: Intent) {
        AppLogger.i("Host", "HOST_START_BEGIN")
        val localIp = NetworkUtils.getLocalIpAddress()

        // 1. Validate and obtain MediaProjection
        val mpManager = try {
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        } catch (e: Exception) {
            AppLogger.e("Host", "Exception getting MediaProjectionManager [class=${e.javaClass.name}, msg=${e.message}]", e)
            null
        }

        if (mpManager == null) {
            AppLogger.e("Host", "MediaProjectionManager is unavailable on this device")
            setFailureState("MediaProjectionManager is unavailable on this device")
            return
        }

        val mp = try {
            mpManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            AppLogger.e("Host", "Exception while calling getMediaProjection() [class=${e.javaClass.name}, msg=${e.message}]", e)
            null
        }

        if (mp == null) {
            AppLogger.e("Host", "MediaProjection is NULL - user cancelled or permission revoked")
            setFailureState("MediaProjection returned null - user consent may have expired or failed")
            return
        }
        mediaProjection = mp
        AppLogger.i("Host", "MEDIA_PROJECTION_OK")

        // Register MediaProjection.Callback
        try {
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    AppLogger.w("Host", "MediaProjection session terminated by system or user")
                    try { screenCapture?.stop() } catch (ignored: Exception) {}
                    screenCapture = null
                    try { screenEncoder?.stop() } catch (ignored: Exception) {}
                    screenEncoder = null
                }
            }
            mp.registerCallback(callback, Handler(Looper.getMainLooper()))
            projectionCallback = callback
        } catch (e: Exception) {
            AppLogger.e("Host", "Failed to register MediaProjection.Callback [class=${e.javaClass.name}, msg=${e.message}]", e)
        }

        // 2. START HOST SERVER FIRST
        AppLogger.i("Host", "SERVER_START_BEGIN")
        val serverStarted = try {
            hostServer.start()
        } catch (e: Exception) {
            AppLogger.e("Host", "Exception while starting HostServer [class=${e.javaClass.name}, msg=${e.message}, port=8887, localIp=$localIp]", e)
            false
        }

        if (!serverStarted) {
            AppLogger.e("Host", "HostServer failed to start on $localIp:8887")
            setFailureState("HostServer failed to start on $localIp:8887")
            return
        }
        AppLogger.i("Host", "SERVER_START_SUCCESS port=8887")

        // 3. Mark Host state to RUNNING immediately so the dashboard updates and Controller can connect
        _isHostRunning.value = true
        _hostError.value = null

        // Refresh device info
        val rootStatus = RootEngine.checkRootStatus()
        val info = DeviceInfo(
            deviceName = Build.MANUFACTURER + " " + Build.MODEL,
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            rootStatus = rootStatus,
            ipAddress = localIp,
            port = NetworkUtils.DEFAULT_WEBSOCKET_PORT,
            isHotspotActive = NetworkUtils.isHotspotInterfaceActive(),
            transport = TransportType.WIFI
        )
        _hostDeviceInfo.value = info
        updateNotification()

        // 4. Start Screen Encoder (Do NOT stop host if this fails; keep server alive)
        AppLogger.i("Host", "SCREEN_ENCODER_BEGIN")
        var encoderSurface: Surface? = null
        try {
            val encoder = ScreenEncoder(serviceScope) { frameBytes ->
                hostServer.sendEncodedFrame(frameBytes)
            }
            screenEncoder = encoder

            val streamProfile = StreamProfile()
            encoderSurface = encoder.prepare(streamProfile)
            if (encoderSurface != null) {
                AppLogger.i("Host", "SCREEN_ENCODER_SUCCESS")
            } else {
                AppLogger.e("Host", "ScreenEncoder prepare returned null surface")
                setFailureState("Hardware video encoder unavailable (server remains online)")
            }
        } catch (e: Exception) {
            AppLogger.e("Host", "ScreenEncoder initialization failed [class=${e.javaClass.name}, msg=${e.message}]", e)
            setFailureState("ScreenEncoder failed: ${e.message} (server remains online)")
        }

        // 5. Start VirtualDisplay Screen Capture (Do NOT stop host if this fails; keep server alive)
        if (encoderSurface != null) {
            try {
                val capture = ScreenCapture(this, mp)
                val captureSuccess = capture.start(encoderSurface, StreamProfile())
                if (captureSuccess) {
                    screenCapture = capture
                    AppLogger.i("Host", "SCREEN_CAPTURE_SUCCESS")
                } else {
                    AppLogger.e("Host", "ScreenCapture start failed")
                    setFailureState("VirtualDisplay creation failed (server remains online)")
                }
            } catch (e: Exception) {
                AppLogger.e("Host", "ScreenCapture start failed [class=${e.javaClass.name}, msg=${e.message}]", e)
                setFailureState("ScreenCapture failed: ${e.message} (server remains online)")
            }
        }

        // Screen recorder (optional)
        try {
            screenRecorder = ScreenRecorder(this, mp)
        } catch (e: Exception) {
            AppLogger.w("Host", "Optional ScreenRecorder initialization skipped: ${e.message}")
        }

        // 6. Start UDP discovery broadcaster
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
            AppLogger.i("Host", "DISCOVERY_STARTED")
        } catch (e: Exception) {
            AppLogger.w("Host", "Discovery broadcaster failed to start: ${e.message}")
        }

        clearPendingProjectionData()
        AppLogger.i("Host", "HOST_START_COMPLETE")
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
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentIntent(pOpenApp)
            .addAction(R.drawable.ic_stat_remote, "Disconnect", pDisconnect)
            .addAction(R.drawable.ic_stat_remote, "Stop Host", pStop)
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
