package com.remotecontrollan.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotecontrollan.model.InstalledAppItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAppBrowserScreen(
    hostIp: String,
    port: Int,
    onClose: () -> Unit
) {
    var appItems by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { HttpClient(CIO) }

    suspend fun loadApps() {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val response = client.get("http://$hostIp:$port/api/apps/list").bodyAsText()
                val array = JSONArray(response)
                val list = mutableListOf<InstalledAppItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        InstalledAppItem(
                            appName = obj.getString("appName"),
                            packageName = obj.getString("packageName"),
                            versionName = obj.optString("versionName", "1.0"),
                            isSystemApp = obj.optBoolean("isSystemApp", false)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    appItems = list
                }
            } catch (e: Exception) {
                // handle error
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Host Installed Applications (${appItems.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { loadApps() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B1F24),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121417)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (appItems.isEmpty()) {
                Text(
                    text = "No applications retrieved",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(appItems) { app ->
                        AppItemRow(
                            app = app,
                            onLaunch = {
                                scope.launch(Dispatchers.IO) {
                                    client.submitForm(
                                        url = "http://$hostIp:$port/api/apps/launch",
                                        formParameters = parameters { append("packageName", app.packageName) }
                                    )
                                }
                            },
                            onStop = {
                                scope.launch(Dispatchers.IO) {
                                    client.submitForm(
                                        url = "http://$hostIp:$port/api/apps/stop",
                                        formParameters = parameters { append("packageName", app.packageName) }
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemRow(
    app: InstalledAppItem,
    onLaunch: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.padding(end = 12.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = app.packageName,
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        Row {
            Button(
                onClick = onLaunch,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6CBD)),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("OPEN", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = onStop,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("STOP", fontSize = 11.sp)
            }
        }
    }
}
