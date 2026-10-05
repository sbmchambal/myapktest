package com.remotecontrollan.network

import com.remotecontrollan.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Transport {
    val transportType: TransportType
    val isConnected: StateFlow<Boolean>
    val incomingMessages: Flow<ControlMessage>
    val incomingFrames: Flow<ByteArray>

    suspend fun connect(targetAddress: String, port: Int): Boolean
    suspend fun disconnect()
    suspend fun send(message: ControlMessage): Boolean
    suspend fun sendRaw(data: ByteArray): Boolean
}
