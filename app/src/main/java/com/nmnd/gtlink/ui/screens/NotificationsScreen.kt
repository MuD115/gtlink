@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.ui.AppEntry
import com.nmnd.gtlink.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val iconCache = LruCache<String, ImageBitmap>(300)

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(shape))
    } else {
        Box(
            Modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun NotificationsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = vm.loadApps() }

    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.orEmpty().filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    }

    Scaffold(topBar = { GtTopBar("Notifications", onBack, busy = busy) }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = listPadding(inner),
        ) {
            item {
                SettingsCard {
                    SwitchRow("Forward notifications", "Show phone notifications on the watch", s.notificationsEnabled) { v ->
                        vm.updateSettings { it.copy(notificationsEnabled = v) }
                    }
                    SwitchRow("Incoming calls", "Show who's calling and let you answer or reject", s.forwardCalls) { v ->
                        vm.updateSettings { it.copy(forwardCalls = v) }
                    }
                    SwitchRow("Skip ongoing notifications", "Ignore music players, downloads and other persistent ones", s.skipOngoing) { v ->
                        vm.updateSettings { it.copy(skipOngoing = v) }
                    }
                    SwitchRow("Only when worn", "Don't buzz the watch while it's off your wrist", s.suppressWhenNotWorn) { v ->
                        vm.updateSettings { it.copy(suppressWhenNotWorn = v) }
                    }
                    SwitchRow("Disconnect alert", "Vibrate the watch if the phone goes out of range", s.disconnectAlert) { v ->
                        vm.updateSettings { it.copy(disconnectAlert = v) }
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { vm.sendTestNotification() },
                        enabled = watch.connected && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send test notification") }
                }
            }
            item {
                Column {
                SectionHeader("Apps")
                GtSearchField(query, { query = it }, "Search apps")
                val blockedCount = s.blockedApps.size
                Text(
                    if (blockedCount == 0) "Notifications from every app are forwarded."
                    else "$blockedCount ${if (blockedCount == 1) "app is" else "apps are"} muted.",
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            if (apps == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "No apps found.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(filtered, key = { it.packageName }) { app ->
                    val allowed = app.packageName !in s.blockedApps
                    SettingsCard(shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(bottom = 6.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setAppBlocked(app.packageName, blocked = allowed) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AppIcon(app.packageName)
                            Text(
                                app.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Switch(
                                checked = allowed,
                                onCheckedChange = { vm.setAppBlocked(app.packageName, blocked = !it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
