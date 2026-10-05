package com.remotecontrollan.host

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.remotecontrollan.model.DeviceInfo
import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.utils.AppLogger
import com.remotecontrollan.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    isHostActive: Boolean,
    deviceInfo: DeviceInfo,
    currentPin: String,
    connectedControllers: Int,
    onStartHostRequested: () -> Unit,
    onStopHostRequested: () -> Unit,
    onRegeneratePin: () -> Unit,
    onSwitchMode: () -> Unit
) {
    val scrollState = rememberScrollState()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo, currentPin) {
        val payload = "remotecontrollan://pair?ip=${deviceInfo.ipAddress}&port=${deviceInfo.port}&pin=$currentPin&device=${deviceInfo.model}"
        qrBitmap = generateQrCode(payload, 512)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HOST MODE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Controlled Device (Redmi Note 10)", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                actions = {
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
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Main State Banner
            StatusBanner(isHostActive = isHostActive, connectedControllers = connectedControllers)

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action Button (Start / Stop)
            if (isHostActive) {
                Button(
                    onClick = onStopHostRequested,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("STOP HOST", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartHostRequested,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6CBD)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START HOST (SCREEN SHARE)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Dashboard Metrics Card
            DashboardMetricsCard(deviceInfo = deviceInfo, isHostActive = isHostActive, connectedControllers = connectedControllers)

            Spacer(modifier = Modifier.height(20.dp))

            // Pairing Security & PIN Card
            PairingSecurityCard(
                pin = currentPin,
                qrBitmap = qrBitmap,
                onRegeneratePin = onRegeneratePin
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Diagnostic & Instructions Card
            SetupInstructionsCard()
        }
    }
}

@Composable
fun StatusBanner(isHostActive: Boolean, connectedControllers: Int) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isHostActive) Color(0xFF064E3B) else Color(0xFF1E242B)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isHostActive) Color(0xFF10B981) else Color(0xFF6B7280))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = if (isHostActive) "HOST ACTIVE" else "HOST IDLE",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isHostActive) {
                        if (connectedControllers > 0) "$connectedControllers controller(s) connected and streaming"
                        else "Server ready • Waiting for Redmi Note 12 to connect"
                    } else {
                        "Tap Start Host to initiate MediaProjection screen capture"
                    },
                    color = if (isHostActive) Color(0xFFA7F3D0) else Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun DashboardMetricsCard(deviceInfo: DeviceInfo, isHostActive: Boolean, connectedControllers: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DEVICE DASHBOARD",
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            MetricRow("Device Name", if (deviceInfo.deviceName.isNotBlank()) deviceInfo.deviceName else "Redmi Note 10")
            MetricRow("Model", if (deviceInfo.model.isNotBlank()) deviceInfo.model else "M2101K7AG")
            MetricRow("Android Version", if (deviceInfo.androidVersion.isNotBlank()) deviceInfo.androidVersion else "Android 13 (MIUI)")
            MetricRow(
                "Root Status",
                if (deviceInfo.rootStatus == RootStatus.DETECTED) "Detected (Allowlisted su active)" else "Not Detected (Using Accessibility)",
                valueColor = if (deviceInfo.rootStatus == RootStatus.DETECTED) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
            MetricRow(
                "Current Connection",
                if (deviceInfo.isHotspotActive) "Wi-Fi Hotspot (Enabled)" else "LAN / Wi-Fi Active",
                valueColor = Color(0xFF38BDF8)
            )
            MetricRow("Transport", if (deviceInfo.transport == TransportType.USB) "USB Host/Accessory" else "Wi-Fi LAN")
            MetricRow("Local IP Address", "${deviceInfo.ipAddress}:${deviceInfo.port}")
            MetricRow(
                "Server Status",
                if (isHostActive) "Listening on 8887 (WS) & 8080 (HTTP)" else "Stopped",
                valueColor = if (isHostActive) Color(0xFF10B981) else Color.Gray
            )
            MetricRow(
                "Connected Controller",
                if (connectedControllers > 0) "Connected ($connectedControllers active)" else "None"
            )
            MetricRow("Screen Stream", if (isHostActive) "720p @ 30 FPS • H.264 AVC" else "Inactive")
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF8B949E), fontSize = 13.sp)
        Text(text = value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PairingSecurityCard(
    pin: String,
    qrBitmap: Bitmap?,
    onRegeneratePin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PAIRING & SECURITY",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onRegeneratePin) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate PIN", tint = Color.LightGray)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 6-digit PIN display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF21262D))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PAIRING PIN", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (pin.isNotBlank()) pin else "123456",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8),
                        letterSpacing = 8.sp
                    )
                    Text("Enter this PIN on Redmi Note 12 controller", fontSize = 11.sp, color = Color.LightGray)
                }
            }

            // QR Code
            if (qrBitmap != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR",
                        modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun SetupInstructionsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "REDMI NOTE 10 → NOTE 12 SETUP",
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "1. Enable Wi-Fi Hotspot on this Redmi Note 10.\n" +
                        "2. Connect Redmi Note 12 to this hotspot (no internet needed).\n" +
                        "3. Tap START HOST above and allow Screen Recording consent.\n" +
                        "4. Open app on Redmi Note 12 in Controller Mode.\n" +
                        "5. Note 12 discovers Note 10 automatically -> Enter 6-digit PIN.",
                color = Color(0xFFC9D1D9),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
