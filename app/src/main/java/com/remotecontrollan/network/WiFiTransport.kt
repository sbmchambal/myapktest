package com.remotecontrollan.network

import com.remotecontrollan.model.TransportType
import com.remotecontrollan.utils.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WiFiTransport(
    private val scope: CoroutineScope
) : Transport {

    override val transportType: TransportType = TransportType.WIFI

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val messageChannel = Channel<ControlMessage>(Channel.BUFFERED)
    override val incomingMessages: Flow<ControlMessage> = messageChannel.receiveAsFlow()

    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
    override val incomingFrames: Flow<ByteArray> = frameChannel.receiveAsFlow()

    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null

    override suspend fun connect(targetAddress: String, port: Int): Boolean {
        disconnect()

        val newClient = HttpClient(CIO) {
            install(WebSockets) {
                pingIntervalMillis = 5000
            }
        }
        client = newClient

        val connectedSignal = CompletableDeferredBoolean()

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                AppLogger.i("WiFiTransport", "Connecting to ws://$targetAddress:$port/control")
                newClient.webSocket(
                    method = HttpMethod.Get,
                    host = targetAddress,
                    port = port,
                    path = "/control"
                ) {
                    session = this
                    _isConnected.value = true
                    connectedSignal.complete(true)
                    AppLogger.i("WiFiTransport", "Connected to host WebSocket")

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                val msg = ProtocolParser.parse(text)
                                if (msg != null) {
                                    messageChannel.trySend(msg)
                                }
                            }
                            is Frame.Binary -> {
                                val bytes = frame.readBytes()
                                frameChannel.trySend(bytes)
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("WiFiTransport", "WebSocket connection error: ${e.message}")
                connectedSignal.complete(false)
            } finally {
                _isConnected.value = false
                session = null
                AppLogger.i("WiFiTransport", "WebSocket disconnected")
            }
        }

        return connectedSignal.await()
    }

    override suspend fun disconnect() {
        try {
            session?.close()
            session = null
            connectJob?.cancel()
            client?.close()
            client = null
        } catch (e: Exception) {
            AppLogger.w("WiFiTransport", "Error during disconnect", e)
        } finally {
            _isConnected.value = false
        }
    }

    override suspend fun send(message: ControlMessage): Boolean {
        val s = session ?: return false
        return try {
            val json = ProtocolParser.serialize(message)
            s.send(Frame.Text(json))
            true
        } catch (e: Exception) {
            AppLogger.e("WiFiTransport", "Failed to send message: ${e.message}")
            false
        }
    }

    override suspend fun sendRaw(data: ByteArray): Boolean {
        val s = session ?: return false
        return try {
            s.send(Frame.Binary(true, data))
            true
        } catch (e: Exception) {
            AppLogger.e("WiFiTransport", "Failed to send binary data: ${e.message}")
            false
        }
    }

    private class CompletableDeferredBoolean {
        private val lock = Object()
        private var completed = false
        private var value = false

        fun complete(v: Boolean) {
            synchronized(lock) {
                if (!completed) {
                    completed = true
                    value = v
                    lock.notifyAll()
                }
            }
        }

        fun await(): Boolean {
            synchronized(lock) {
                while (!completed) {
                    try {
                        lock.wait(5000)
                        break
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
                return value
            }
        }
    }
}
