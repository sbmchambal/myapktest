package com.remotecontrollan.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.remotecontrollan.model.RemoteFileItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFileBrowserScreen(
    hostIp: String,
    port: Int,
    onClose: () -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var fileItems by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val client = remember { HttpClient(CIO) }

    suspend fun loadPath(path: String) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val url = if (path.isEmpty()) {
                    "http://$hostIp:$port/api/files/roots"
                } else {
                    "http://$hostIp:$port/api/files/list?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
                }
                val response = client.get(url).bodyAsText()
                val array = JSONArray(response)
                val list = mutableListOf<RemoteFileItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        RemoteFileItem(
                            name = obj.getString("name"),
                            path = obj.getString("path"),
                            isDirectory = obj.getBoolean("isDirectory"),
                            sizeBytes = obj.optLong("sizeBytes", 0),
                            lastModified = obj.optLong("lastModified", 0)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    fileItems = list
                    currentPath = path
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
        loadPath("")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Host File Manager", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (currentPath.isEmpty()) "Root storage categories" else currentPath,
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.isNotEmpty()) {
                            // Go back to roots
                            scope.launch { loadPath("") }
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                    IconButton(onClick = { scope.launch { loadPath(currentPath) } }) {
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
            } else if (fileItems.isEmpty()) {
                Text(
                    text = "No files found in directory",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(fileItems) { item ->
                        FileItemRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    scope.launch { loadPath(item.path) }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        client.submitForm(
                                            url = "http://$hostIp:$port/api/files/delete",
                                            formParameters = parameters {
                                                append("path", item.path)
                                            }
                                        )
                                    }
                                    loadPath(currentPath)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNewFolderDialog = false
                    if (newFolderName.isNotBlank() && currentPath.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                client.submitForm(
                                    url = "http://$hostIp:$port/api/files/mkdir",
                                    formParameters = parameters {
                                        append("parent", currentPath)
                                        append("name", newFolderName)
                                    }
                                )
                            }
                            newFolderName = ""
                            loadPath(currentPath)
                        }
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FileItemRow(
    item: RemoteFileItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (item.isDirectory) Color(0xFFFBBF24) else Color(0xFF60A5FA),
            modifier = Modifier.size(32.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val subtext = if (item.isDirectory) {
                "Directory"
            } else {
                "${formatFileSize(item.sizeBytes)} • ${SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(item.lastModified))}"
            }
            Text(
                text = subtext,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
