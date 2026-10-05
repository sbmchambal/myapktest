package com.remotecontrollan.host

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.remotecontrollan.model.DeviceInfo
import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.network.ControlMessage
import com.remotecontrollan.network.ProtocolParser
import com.remotecontrollan.pairing.PairingManager
import com.remotecontrollan.utils.AppLogger
import com.remotecontrollan.utils.NetworkUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

class HostServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairingManager: PairingManager,
    private val inputEngine: InputEngine,
    private val fileManager: FileManager,
    private val appManager: AppManager,
    private val clipboardSync: ClipboardSync,
    private val port: Int = NetworkUtils.DEFAULT_WEBSOCKET_PORT
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val activeSessions = CopyOnWriteArraySet<DefaultWebSocketServerSession>()

    private val _connectedControllersCount = MutableStateFlow(0)
    val connectedControllersCount: StateFlow<Int> = _connectedControllersCount.asStateFlow()

    private val _activeControllerName = MutableStateFlow<String?>("None")
    val activeControllerName: StateFlow<String?> = _activeControllerName.asStateFlow()

    fun start(): Boolean {
        if (engine != null) return true

        val localIp = NetworkUtils.getLocalIpAddress()
        AppLogger.i("HostServer", "SERVER_START_BEGIN host=0.0.0.0 port=$port localIp=$localIp")

        return try {
            val server = embeddedServer(
                factory = CIO,
                host = "0.0.0.0",
                port = port
            ) {
                install(WebSockets) {
                    pingPeriodMillis = 5000
                    timeoutMillis = 15000
                }
                install(ContentNegotiation) {
                    json()
                }

                routing {
                    // Explicit HTTP Health Check endpoint (unauthenticated)
                    get("/health") {
                        val resp = JSONObject().apply {
                            put("status", "ok")
                            put("service", "RemoteControlLAN")
                            put("port", port)
                        }
                        call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                    }

                    // WebSocket real-time control & screen streaming
                    webSocket("/control") {
                        handleWebSocketSession(this)
                    }

                    // Device info endpoint
                    get("/api/device-info") {
                        val authHeader = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: ""
                        val controllerId = call.request.headers["X-Controller-ID"] ?: ""
                        if (!pairingManager.authenticate(controllerId, authHeader)) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@get
                        }

                        val info = getDeviceInfo()
                        val json = JSONObject().apply {
                            put("deviceName", info.deviceName)
                            put("model", info.model)
                            put("androidVersion", info.androidVersion)
                            put("screenWidth", info.screenWidth)
                            put("screenHeight", info.screenHeight)
                            put("rootStatus", info.rootStatus.name)
                            put("ipAddress", info.ipAddress)
                        }
                        call.respondText(json.toString(), ContentType.Application.Json)
                    }

                    // Files API
                    get("/api/files/roots") {
                        val roots = fileManager.getStandardRoots()
                        val array = JSONArray()
                        for (r in roots) {
                            array.put(JSONObject().apply {
                                put("name", r.name)
                                put("path", r.path)
                                put("isDirectory", r.isDirectory)
                            })
                        }
                        call.respondText(array.toString(), ContentType.Application.Json)
                    }

                    get("/api/files/list") {
                        val path = call.request.queryParameters["path"] ?: ""
                        val files = fileManager.listFiles(path)
                        val array = JSONArray()
                        for (f in files) {
                            array.put(JSONObject().apply {
                                put("name", f.name)
                                put("path", f.path)
                                put("isDirectory", f.isDirectory)
                                put("sizeBytes", f.sizeBytes)
                                put("lastModified", f.lastModified)
                            })
                        }
                        call.respondText(array.toString(), ContentType.Application.Json)
                    }

                    get("/api/files/download") {
                        val path = call.request.queryParameters["path"] ?: ""
                        val stream = fileManager.getInputStream(path)
                        if (stream == null) {
                            call.respond(HttpStatusCode.NotFound, "File not found or access denied")
                        } else {
                            val bytes = stream.use { it.readBytes() }
                            call.respondBytes(bytes, ContentType.Application.OctetStream)
                        }
                    }

                    post("/api/files/delete") {
                        val params = call.receiveParameters()
                        val path = params["path"] ?: ""
                        val success = fileManager.delete(path)
                        call.respondText(JSONObject().put("success", success).toString(), ContentType.Application.Json)
                    }

                    post("/api/files/mkdir") {
                        val params = call.receiveParameters()
                        val parent = params["parent"] ?: ""
                        val name = params["name"] ?: ""
                        val success = fileManager.createDirectory(parent, name)
                        call.respondText(JSONObject().put("success", success).toString(), ContentType.Application.Json)
                    }

                    // Apps API
                    get("/api/apps/list") {
                        val apps = appManager.getInstalledApps()
                        val array = JSONArray()
                        for (a in apps) {
                            array.put(JSONObject().apply {
                                put("appName", a.appName)
                                put("packageName", a.packageName)
                                put("versionName", a.versionName)
                                put("isSystemApp", a.isSystemApp)
                            })
                        }
                        call.respondText(array.toString(), ContentType.Application.Json)
                    }

                    post("/api/apps/launch") {
                        val params = call.receiveParameters()
                        val pkg = params["packageName"] ?: ""
                        val success = appManager.launchApp(pkg)
                        call.respondText(JSONObject().put("success", success).toString(), ContentType.Application.Json)
                    }

                    post("/api/apps/stop") {
                        val params = call.receiveParameters()
                        val pkg = params["packageName"] ?: ""
                        val success = appManager.stopApp(pkg)
                        call.respondText(JSONObject().put("success", success).toString(), ContentType.Application.Json)
                    }
                }
            server.start(wait = false)
            engine = server
            AppLogger.i("HostServer", "SERVER_START_SUCCESS port=$port")
            true
        } catch (e: Exception) {
            val exceptionClass = e.javaClass.name
            val message = e.message ?: "Unknown error"
            AppLogger.e("HostServer", "HostServer failed to start on $localIp:$port [class=$exceptionClass, message=$message]", e)
            if (e is java.net.BindException || (e.cause is java.net.BindException)) {
                AppLogger.e("HostServer", "Port $port is already in use by another process or previous instance")
            }
            engine = null
            false
        }
    }

    private suspend fun handleWebSocketSession(session: DefaultWebSocketServerSession) {
        var isAuthorized = false
        var currentControllerId = ""
        var currentControllerName = ""

        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val raw = frame.readText()
                    val msg = ProtocolParser.parse(raw) ?: continue

                    // Handshake / Auth / Pairing
                    when (msg) {
                        is ControlMessage.Auth -> {
                            if (pairingManager.authenticate(msg.controllerId, msg.token)) {
                                isAuthorized = true
                                currentControllerId = msg.controllerId
                                currentControllerName = msg.deviceName
                                activeSessions.add(session)
                                _connectedControllersCount.value = activeSessions.size
                                _activeControllerName.value = currentControllerName
                                session.send(Frame.Text(ProtocolParser.serialize(
                                    ControlMessage.AuthResponse(true, "Authentication successful")
                                )))
                                AppLogger.i("HostServer", "Controller authenticated: ${msg.deviceName}")
                            } else {
                                session.send(Frame.Text(ProtocolParser.serialize(
                                    ControlMessage.AuthResponse(false, "Invalid authentication token")
                                )))
                            }
                            continue
                        }
                        is ControlMessage.PairingRequest -> {
                            val token = pairingManager.verifyPinAndIssueToken(
                                msg.controllerId,
                                msg.deviceName,
                                msg.pin
                            )
                            if (token != null) {
                                isAuthorized = true
                                currentControllerId = msg.controllerId
                                currentControllerName = msg.deviceName
                                activeSessions.add(session)
                                _connectedControllersCount.value = activeSessions.size
                                _activeControllerName.value = currentControllerName
                                session.send(Frame.Text(ProtocolParser.serialize(
                                    ControlMessage.PairingResponse(true, token, "Paired successfully")
                                )))
                                AppLogger.i("HostServer", "Pairing accepted for: ${msg.deviceName}")
                            } else {
                                session.send(Frame.Text(ProtocolParser.serialize(
                                    ControlMessage.PairingResponse(false, null, "Invalid 6-digit PIN")
                                )))
                            }
                            continue
                        }
                        else -> Unit
                    }

                    // Security check: only authorized sessions can execute commands
                    if (!isAuthorized) {
                        session.send(Frame.Text(ProtocolParser.serialize(
                            ControlMessage.AuthResponse(false, "Unauthorized: Pairing required")
                        )))
                        continue
                    }

                    // Dispatch control commands
                    processAuthorizedCommand(msg, session)
                }
            }
        } catch (e: Exception) {
            AppLogger.w("HostServer", "Session exception: ${e.message}")
        } finally {
            activeSessions.remove(session)
            _connectedControllersCount.value = activeSessions.size
            if (activeSessions.isEmpty()) {
                _activeControllerName.value = "None"
            }
            AppLogger.i("HostServer", "Controller disconnected: $currentControllerName")
        }
    }

    private suspend fun processAuthorizedCommand(msg: ControlMessage, session: DefaultWebSocketServerSession) {
        when (msg) {
            is ControlMessage.Tap -> inputEngine.handleTap(msg.x, msg.y)
            is ControlMessage.Touch -> inputEngine.handleTouch(msg.action, msg.x, msg.y)
            is ControlMessage.Swipe -> inputEngine.handleSwipe(msg.x1, msg.y1, msg.x2, msg.y2, msg.durationMs)
            is ControlMessage.Key -> inputEngine.handleKey(msg.keyCode)
            is ControlMessage.Text -> inputEngine.handleText(msg.text)
            is ControlMessage.Back -> inputEngine.handleBack()
            is ControlMessage.Home -> inputEngine.handleHome()
            is ControlMessage.Recent -> inputEngine.handleRecent()
            is ControlMessage.VolumeUp -> inputEngine.handleVolumeUp()
            is ControlMessage.VolumeDown -> inputEngine.handleVolumeDown()
            is ControlMessage.Power -> inputEngine.handlePower()
            is ControlMessage.LaunchApp -> appManager.launchApp(msg.packageName)
            is ControlMessage.CloseApp -> appManager.stopApp(msg.packageName)
            is ControlMessage.ClipboardSend -> clipboardSync.setClipboardText(msg.text)
            is ControlMessage.ClipboardRequest -> {
                val text = clipboardSync.getClipboardText()
                session.send(Frame.Text(ProtocolParser.serialize(ControlMessage.ClipboardResponse(text))))
            }
            is ControlMessage.Ping -> {
                session.send(Frame.Text(ProtocolParser.serialize(ControlMessage.Pong(msg.timestamp))))
            }
            else -> Unit
        }
    }

    /**
     * Broadcasts an encoded video frame to all connected, authorized controllers.
     */
    fun sendEncodedFrame(frameBytes: ByteArray) {
        if (activeSessions.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val frame = Frame.Binary(true, frameBytes)
            for (session in activeSessions) {
                try {
                    session.send(frame)
                } catch (e: Exception) {
                    // Frame drop on slow client
                }
            }
        }
    }

    fun disconnectAll() {
        scope.launch(Dispatchers.IO) {
            for (s in activeSessions) {
                try {
                    s.close()
                } catch (ignored: Exception) {}
            }
            activeSessions.clear()
            _connectedControllersCount.value = 0
            _activeControllerName.value = "None"
        }
    }

    fun stop() {
        disconnectAll()
        engine?.stop(1000, 2000)
        engine = null
        AppLogger.i("HostServer", "HostServer stopped")
    }

    private fun getDeviceInfo(): DeviceInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        return DeviceInfo(
            deviceName = Build.MANUFACTURER + " " + Build.MODEL,
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            screenDensityDpi = metrics.densityDpi,
            rootStatus = RootEngine.checkRootStatus(),
            ipAddress = NetworkUtils.getLocalIpAddress(),
            port = port,
            isHotspotActive = NetworkUtils.isHotspotInterfaceActive(),
            transport = TransportType.WIFI
        )
    }
}
