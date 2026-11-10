@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.link.UploadState
import com.nmnd.gtlink.theme.LocalGtExtras
import com.nmnd.gtlink.ui.AppViewModel

@Composable
fun InstallScreen(vm: AppViewModel, onBack: () -> Unit) {
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val upload by vm.uploadState.collectAsStateWithLifecycle()

    val running = upload is UploadState.Running
    val canPick = watch.connected && !running

    val pick: (Uri?) -> Unit = { uri -> if (uri != null) vm.install(uri) }
    val faceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick)
    val appLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick)
    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick)

    val w = watch.screenWidth
    val h = watch.screenHeight
    val screenText = if (w != null && h != null) "$w×$h" else null

    Scaffold(topBar = { GtTopBar("Install", onBack, busy = running) }) { inner ->
        ScreenColumn(inner) {
            if (!watch.connected) {
                InfoBanner("Connect your watch first. Files can only be sent while it's connected.")
            }

            UploadStatus(upload, onCancel = { vm.cancelUpload() })

            SectionHeader("Send to watch")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstallCard(
                    icon = Icons.Rounded.Watch,
                    title = "Watch face (.hwt)",
                    subtitle = if (screenText != null) "Made for a $screenText screen" else "Pick a watch face file",
                    enabled = canPick,
                ) { faceLauncher.launch(arrayOf("*/*")) }
                InstallCard(
                    icon = Icons.Rounded.Apps,
                    title = "Watch app (.bin)",
                    subtitle = "Pick a watch app package",
                    enabled = canPick,
                ) { appLauncher.launch(arrayOf("*/*")) }
                InstallCard(
                    icon = Icons.Rounded.MusicNote,
                    title = "Music",
                    subtitle = "MP3 or AAC audio files",
                    enabled = canPick,
                ) { musicLauncher.launch(arrayOf("audio/*")) }
            }

            SectionHeader("Good to know")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Note(
                        "Watch faces",
                        "Faces from the Huawei Theme store can't be downloaded directly. Community-made .hwt files " +
                            "built for the 466×466 GT 4 screen work. The watch rejects faces made for other screens" +
                            (if (screenText != null) " (yours is $screenText)." else "."),
                    )
                    Note(
                        "Music",
                        "Songs must be MP3 or AAC. The watch stores them in its own music app.",
                    )
                    Note(
                        "Experimental",
                        "Installing files is experimental. Keep the watch close and the screen on until the transfer finishes.",
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadStatus(state: UploadState, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        UploadState.Idle -> Unit
        is UploadState.Running -> {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Sending ${state.title}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${state.percent}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.primary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        is UploadState.Done -> StatusCard(
            icon = Icons.Rounded.CheckCircle,
            title = "Installed ${state.title}",
            detail = "It's on your watch now.",
            container = cs.tertiaryContainer,
            content = cs.onTertiaryContainer,
        )
        is UploadState.Failed -> StatusCard(
            icon = Icons.Rounded.Error,
            title = "Couldn't install ${state.title}",
            detail = state.reason,
            container = cs.errorContainer,
            content = cs.onErrorContainer,
        )
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    detail: String,
    container: Color,
    content: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InstallCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsCard(Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = LocalGtExtras.current.metadata)
        Spacer(Modifier.height(2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
