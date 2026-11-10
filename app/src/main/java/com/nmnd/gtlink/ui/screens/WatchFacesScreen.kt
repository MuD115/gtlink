@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.link.WatchFace
import com.nmnd.gtlink.ui.AppViewModel

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun WatchFacesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val faces = watch.watchFaces
    var deleting by remember { mutableStateOf<WatchFace?>(null) }

    LaunchedEffect(watch.connected) {
        if (watch.connected && watch.watchFaces.isEmpty()) vm.refreshWatchfaces()
    }

    Scaffold(
        topBar = {
            GtTopBar("Watch faces", onBack, busy = busy) {
                IconButton(onClick = { vm.refreshWatchfaces() }, enabled = watch.connected && !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = listPadding(inner)) {
            if (faces.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (watch.connected) "No watch faces reported" else "Watch not connected",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (watch.connected) "Pull the list again with the refresh button." else "Connect to your watch to see and switch its faces.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(faces) { face ->
                    SettingsCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = watch.connected && !busy && !face.current) { vm.setWatchface(face) }
                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (face.factory || face.current) 16.dp else 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    face.displayName ?: face.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (face.version.isNotBlank()) {
                                        Text(
                                            "v${face.version}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (face.current) {
                                        Pill("Active", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    if (face.factory) {
                                        Pill("Built-in", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (!face.factory && !face.current) {
                                IconButton(onClick = { deleting = face }, enabled = watch.connected && !busy) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        "Tap a face to switch to it. Installing new watch faces from the phone isn't supported yet.",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deleting?.let { face ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove watch face?") },
            text = { Text("\"${face.displayName ?: face.fileName}\" will be deleted from the watch.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.deleteWatchface(face)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
