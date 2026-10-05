package com.remotecontrollan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.remotecontrollan.controller.ControllerScreen
import com.remotecontrollan.host.HostScreen
import com.remotecontrollan.host.HostService
import com.remotecontrollan.model.AppMode
import com.remotecontrollan.model.DeviceInfo
import com.remotecontrollan.model.RootStatus
import com.remotecontrollan.model.TransportType
import com.remotecontrollan.settings.SettingsManager
import com.remotecontrollan.ui.theme.RemoteControlLANTheme
import com.remotecontrollan.utils.AppLogger
import com.remotecontrollan.utils.NetworkUtils

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager

    // MediaProjection permission launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, HostService::class.java).apply {
                action = HostService.ACTION_START
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            AppLogger.i("MainActivity", "MediaProjection granted, started HostService")
        } else {
            Toast.makeText(this, "Screen capture permission is required for Host Mode", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            AppLogger.w("MainActivity", "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            RemoteControlLANTheme {
                val appMode by settingsManager.appMode.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (appMode) {
                        AppMode.UNSELECTED -> ModeSelectionScreen(
                            onSelectMode = { mode -> settingsManager.setAppMode(mode) }
                        )
                        AppMode.HOST -> {
                            val hostService = HostService.getInstance()
                            val isHostActive = hostService?.isHostRunning?.collectAsState()?.value ?: false
                            val deviceInfo = hostService?.hostDeviceInfo?.collectAsState()?.value ?: DeviceInfo(
                                deviceName = Build.MANUFACTURER + " " + Build.MODEL,
                                model = Build.MODEL,
                                androidVersion = "Android ${Build.VERSION.RELEASE}",
                                rootStatus = com.remotecontrollan.host.RootEngine.checkRootStatus(),
                                ipAddress = NetworkUtils.getLocalIpAddress(),
                                isHotspotActive = NetworkUtils.isHotspotInterfaceActive()
                            )
                            val pin = hostService?.currentPin?.collectAsState()?.value ?: "123456"
                            val connectedCount = hostService?.connectedControllers?.collectAsState()?.value ?: 0

                            HostScreen(
                                isHostActive = isHostActive,
                                deviceInfo = deviceInfo,
                                currentPin = pin,
                                connectedControllers = connectedCount,
                                onStartHostRequested = { requestMediaProjection() },
                                onStopHostRequested = { stopHostService() },
                                onRegeneratePin = { hostService?.regeneratePairingPin() },
                                onSwitchMode = { settingsManager.setAppMode(AppMode.UNSELECTED) }
                            )
                        }
                        AppMode.CONTROLLER -> {
                            ControllerScreen(
                                onSwitchMode = { settingsManager.setAppMode(AppMode.UNSELECTED) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun stopHostService() {
        val intent = Intent(this, HostService::class.java).apply {
            action = HostService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun ModeSelectionScreen(
    onSelectMode: (AppMode) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "REMOTE CONTROL LAN",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )

            Text(
                text = "Direct Android-to-Android Control over Wi-Fi / Hotspot / USB",
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
            )

            // HOST MODE Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cast, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("HOST MODE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Phone being controlled (e.g. Redmi Note 10 with Hotspot ON). Streams screen and receives touch commands.",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Button(
                        onClick = { onSelectMode(AppMode.HOST) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6CBD)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("HOST MODE", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // CONTROLLER MODE Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Gamepad, contentDescription = null, tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("CONTROLLER MODE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Phone controlling the other device (e.g. Redmi Note 12). Discovers host on hotspot, displays live screen, and sends touch/keys.",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Button(
                        onClick = { onSelectMode(AppMode.CONTROLLER) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6CBD)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("CONTROLLER MODE", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Mode can be changed anytime from the top bar icon.",
                color = Color(0xFF6B7280),
                fontSize = 11.sp
            )
        }
    }
}
