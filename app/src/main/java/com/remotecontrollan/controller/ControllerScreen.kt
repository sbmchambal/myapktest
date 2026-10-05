package com.remotecontrollan.controller

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotecontrollan.model.ConnectionStatus
import com.remotecontrollan.model.DiscoveredHost
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.network.ControlMessage
import com.remotecontrollan.network.ControllerDiscoveryScanner
import com.remotecontrollan.network.WebSocketManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    onSwitchMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val discoveryScanner = remember { ControllerDiscoveryScanner(scope) }
    val wsManager = remember { WebSocketManager(context, scope) }

    val discoveredHosts by discoveryScanner.discoveredHosts.collectAsState()
    val connectionStatus by wsManager.connectionStatus.collectAsState()
    val metrics by wsManager.metrics.collectAsState()
    val statusMsg by wsManager.statusMessage.collectAsState()
    val activeTransport by wsManager.activeTransportType.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var selectedHost by remember { mutableStateOf<DiscoveredHost?>(null) }

    var showManualIpDialog by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("192.168.43.1") }

    var showKeyboardDialog by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
    var showAppManager by remember { mutableStateOf(false) }
    var showPowerConfirmDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        discoveryScanner.startScanning()
        onDispose {
            discoveryScanner.stopScanning()
            wsManager.disconnect()
        }
    }

    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.PAIRING_REQUIRED) {
            showPinDialog = true
        }
    }

    // Sub-screens: File manager or App browser
    if (showFileManager && selectedHost != null) {
        RemoteFileBrowserScreen(
            hostIp = selectedHost!!.ipAddress,
            port = 8080,
            onClose = { showFileManager = false }
        )
        return
    }

    if (showAppManager && selectedHost != null) {
        RemoteAppBrowserScreen(
            hostIp = selectedHost!!.ipAddress,
            port = 8080,
            onClose = { showAppManager = false }
        )
        return
    }

    // Active Remote Control View
    if (connectionStatus == ConnectionStatus.CONNECTED) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Main remote display surface
            RemoteDisplay(
                metrics = metrics,
                hostWidth = 1080,
                hostHeight = 2400,
                onSendCommand = { wsManager.sendCommand(it) },
                modifier = Modifier.fillMaxSize()
            )

            // Top Status Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0x99111827))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedHost?.deviceName ?: "Remote Host",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " (${if (activeTransport == TransportType.USB) "USB" else "Wi-Fi"})",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp
                    )
                }

                Row {
                    IconButton(onClick = { showKeyboardDialog = true }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Keyboard", tint = Color.White)
                    }
                    IconButton(onClick = { showFileManager = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Files", tint = Color.White)
                    }
                    IconButton(onClick = { showAppManager = true }) {
                        Icon(Icons.Default.Apps, contentDescription = "Apps", tint = Color.White)
                    }
                    IconButton(onClick = {
                        wsManager.sendCommand(ControlMessage.Screenshot)
                        Toast.makeText(context, "Screenshot requested", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Screenshot, contentDescription = "Screenshot", tint = Color.White)
                    }
                    IconButton(onClick = {
                        wsManager.sendCommand(ControlMessage.ClipboardRequest)
                        Toast.makeText(context, "Requested Host Clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Clipboard", tint = Color.White)
                    }
                    IconButton(onClick = { wsManager.disconnect() }) {
                        Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = Color(0xFFEF4444))
                    }
                }
            }

            // Bottom Navigation Overlay (Back, Home, Recents, Vol -, Vol +, Power)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC0D1117))
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { wsManager.sendCommand(ControlMessage.Back) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.LightGray)
                }
                IconButton(onClick = { wsManager.sendCommand(ControlMessage.Home) }) {
                    Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White)
                }
                IconButton(onClick = { wsManager.sendCommand(ControlMessage.Recent) }) {
                    Icon(Icons.Default.ViewCarousel, contentDescription = "Recents", tint = Color.LightGray)
                }
                IconButton(onClick = { wsManager.sendCommand(ControlMessage.VolumeDown) }) {
                    Icon(Icons.Default.VolumeDown, contentDescription = "Vol -", tint = Color.LightGray)
                }
                IconButton(onClick = { wsManager.sendCommand(ControlMessage.VolumeUp) }) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Vol +", tint = Color.LightGray)
                }
                IconButton(onClick = { showPowerConfirmDialog = true }) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power", tint = Color(0xFFF87171))
                }
            }
        }

        if (showKeyboardDialog) {
            RemoteKeyboardDialog(
                onDismiss = { showKeyboardDialog = false },
                onSendText = { wsManager.sendCommand(ControlMessage.Text(it)) },
                onSendKey = { wsManager.sendCommand(ControlMessage.Key(it)) }
            )
        }

        if (showPowerConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showPowerConfirmDialog = false },
                title = { Text("Power Operation") },
                text = { Text("Trigger remote power action on the host device?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showPowerConfirmDialog = false
                            wsManager.sendCommand(ControlMessage.Power)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Execute Power")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPowerConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        return
    }

    // Discovery / Connect Screen
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CONTROLLER MODE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Scanning local LAN & Hotspot...", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                actions = {
                    IconButton(onClick = { showManualIpDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add IP", tint = Color(0xFF38BDF8))
                    }
                    IconButton(onClick = onSwitchMode) {
                        Icon(Icons.Default.Refresh, contentDescription = "Switch Mode", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B1F24),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121417)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1F24)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.RECONNECTING) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp), strokeWidth = 2.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (connectionStatus == ConnectionStatus.CONNECTED) Color(0xFF10B981) else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column {
                        Text(
                            text = if (statusMsg.isNotBlank()) statusMsg else "Ready to connect",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Redmi Note 12 Controller",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Text(
                text = "REMOTE HOSTS",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (discoveredHosts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF0F6CBD))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Searching for Redmi Note 10 on Hotspot...",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                discoveryScanner.addManualHost("192.168.43.1", 8887, "Hotspot Host (Redmi Note 10)")
                            }
                        ) {
                            Text("Connect to Default Hotspot (192.168.43.1)")
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(discoveredHosts) { host ->
                        DiscoveredHostCard(
                            host = host,
                            onConnect = {
                                selectedHost = host
                                wsManager.connect(host.ipAddress, host.port, host.hostId)
                            }
                        )
                    }
                }
            }
        }
    }

    // 6-digit PIN Entry Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Enter 6-Digit Pairing PIN") },
            text = {
                Column {
                    Text(
                        "Enter the PIN currently displayed on the Host device's screen to authorize this controller:",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { if (it.length <= 6) enteredPin = it },
                        label = { Text("6-digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredPin.length == 6) {
                            showPinDialog = false
                            wsManager.submitPin(enteredPin)
                        }
                    },
                    enabled = enteredPin.length == 6
                ) {
                    Text("Pair & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    wsManager.disconnect()
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Manual IP Entry Dialog
    if (showManualIpDialog) {
        AlertDialog(
            onDismissRequest = { showManualIpDialog = false },
            title = { Text("Connect to Manual IP") },
            text = {
                Column {
                    Text("Enter Host IP address (e.g. 192.168.43.1):", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showManualIpDialog = false
                    if (manualIp.isNotBlank()) {
                        discoveryScanner.addManualHost(manualIp.trim(), 8887, "Host ($manualIp)")
                        val host = DiscoveredHost(
                            hostId = "manual_$manualIp",
                            deviceName = "Host ($manualIp)",
                            model = "Redmi Note 10",
                            ipAddress = manualIp.trim(),
                            port = 8887,
                            isRooted = true
                        )
                        selectedHost = host
                        wsManager.connect(manualIp.trim(), 8887, host.hostId)
                    }
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DiscoveredHostCard(
    host: DiscoveredHost,
    onConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1F24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(36.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = host.deviceName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${host.ipAddress}:${host.port}",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp
                    )
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = if (host.isRooted) "Rooted" else "Standard",
                            color = if (host.isRooted) Color(0xFF10B981) else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = " • Ready",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6CBD))
            ) {
                Text("CONNECT")
            }
        }
    }
}
