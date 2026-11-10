package com.nmnd.gtlink.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.link.ActivityTotals
import com.nmnd.gtlink.link.LinkState
import com.nmnd.gtlink.link.WatchState
import com.nmnd.gtlink.theme.GtExtras
import com.nmnd.gtlink.theme.LocalGtExtras
import com.nmnd.gtlink.theme.MetricNumber
import com.nmnd.gtlink.ui.camera.CameraActivity
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

private enum class HomeDialog { None, Forget, Reset }

/** A tile on the home grid. A null [route] marks the camera remote, which opens its own activity. */
private class Feature(
    val route: Route?,
    val icon: ImageVector,
    val title: String,
    val caption: String,
    val enabled: Boolean = true,
)

private fun featureRows(cameraSupported: Boolean, cameraEnabled: Boolean): List<List<Feature>> = listOf(
    Feature(Route.Settings, Icons.Rounded.Tune, "Watch settings", "Display, health, units"),
    Feature(Route.Notifications, Icons.Rounded.Notifications, "Notifications", "Which apps reach you"),
    Feature(Route.Alarms, Icons.Rounded.Alarm, "Alarms", "Event and smart alarm"),
    Feature(Route.WatchFaces, Icons.Rounded.Palette, "Watch faces", "Switch or remove"),
    Feature(Route.Install, Icons.Rounded.FileUpload, "Install", "Faces, apps, music"),
    Feature(Route.History, Icons.Rounded.MonitorHeart, "Health history", "Steps, heart, sleep"),
    Feature(Route.Contacts, Icons.Rounded.Contacts, "Contacts", "Send to the watch"),
    Feature(Route.Weather, Icons.Rounded.WbSunny, "Weather", "Place and updates"),
    Feature(
        null, Icons.Rounded.PhotoCamera, "Camera remote",
        when {
            !cameraSupported -> "Not on this watch"
            !cameraEnabled -> "Off in settings"
            else -> "Shutter on your wrist"
        },
        enabled = cameraSupported,
    ),
    Feature(Route.Permissions, Icons.Rounded.Security, "Permissions", "Checklist"),
    Feature(Route.Developer, Icons.Rounded.Code, "Developer log", "Raw protocol"),
).chunked(2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onNavigate: (Route) -> Unit) {
    val state by vm.watchState.collectAsStateWithLifecycle()
    val saved by vm.savedWatch.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(HomeDialog.None) }
    val context = LocalContext.current
    val rows = remember(state.canCameraRemote, settings.cameraRemote) {
        featureRows(state.canCameraRemote, settings.cameraRemote)
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(title = { Text("GT Link", style = MaterialTheme.typography.headlineSmall) })
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
    ) { inner ->
        val dir = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + inner.calculateStartPadding(dir),
                end = 16.dp + inner.calculateEndPadding(dir),
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HeaderCard(
                    state = state,
                    name = state.info?.name ?: saved?.name ?: "Your watch",
                    fallbackSubtitle = saved?.address,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                )
            }
            item {
                ActivityCard(
                    activity = state.activity,
                    goal = settings.stepGoal,
                    now = now,
                    canRefresh = state.connected,
                    onRefresh = vm::refresh,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    QuickAction(Icons.Rounded.Schedule, "Sync time", state.connected, vm::syncTime)
                    QuickAction(Icons.Rounded.WbSunny, "Weather", state.connected, vm::sendWeatherNow)
                    QuickAction(Icons.Rounded.NotificationsActive, "Test alert", state.connected, vm::sendTestNotification)
                    QuickAction(Icons.Rounded.Refresh, "Refresh", state.connected, vm::refresh)
                }
            }
            item { SectionLabel("Features") }
            items(rows.size) { i ->
                val pair = rows[i]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    pair.forEach { f ->
                        FeatureTile(f, onClick = {
                            if (f.route != null) onNavigate(f.route)
                            else runCatching { context.startActivity(Intent(context, CameraActivity::class.java)) }
                        })
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { SectionLabel("Watch") }
            item {
                WatchSection(
                    connected = state.connected,
                    onSwitch = { onNavigate(Route.Pairing) },
                    onForget = { dialog = HomeDialog.Forget },
                    onReset = { dialog = HomeDialog.Reset },
                )
            }
        }
    }

    when (dialog) {
        HomeDialog.None -> Unit
        HomeDialog.Forget -> AlertDialog(
            onDismissRequest = { dialog = HomeDialog.None },
            title = { Text("Forget this watch?") },
            text = {
                Text(
                    "GT Link will disconnect and delete the pairing key it holds for this watch. " +
                        "You'll have to pair again before it works. Nothing on the watch itself is erased.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = HomeDialog.None
                    vm.forgetWatch()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { dialog = HomeDialog.None }) { Text("Cancel") } },
        )
        HomeDialog.Reset -> FactoryResetDialog(
            onDismiss = { dialog = HomeDialog.None },
            onConfirm = {
                dialog = HomeDialog.None
                vm.factoryReset()
            },
        )
    }
}

@Composable
private fun FactoryResetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Factory reset the watch?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This erases everything on the watch: watch faces, alarms, contacts, settings and any health " +
                        "data that hasn't been synced. The pairing with this phone is removed too. It cannot be undone.",
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type RESET to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed.trim().equals("RESET", ignoreCase = true),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Erase watch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalGtExtras.current.metadata,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

private fun linkColor(link: LinkState, extras: GtExtras): Color = when (link) {
    LinkState.CONNECTED -> extras.connected
    LinkState.CONNECTING, LinkState.AUTHENTICATING, LinkState.WAITING_TO_RETRY -> extras.connecting
    LinkState.IDLE -> extras.disconnected
}

private fun linkLabel(state: WatchState): String = state.status.ifBlank {
    when (state.link) {
        LinkState.CONNECTED -> "Connected"
        LinkState.CONNECTING -> "Connecting"
        LinkState.AUTHENTICATING -> "Authenticating"
        LinkState.WAITING_TO_RETRY -> "Waiting to retry"
        LinkState.IDLE -> "Not connected"
    }
}

@Composable
private fun gtCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun HeaderCard(
    state: WatchState,
    name: String,
    fallbackSubtitle: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val extras = LocalGtExtras.current
    val tint = linkColor(state.link, extras)
    val subtitle = listOfNotNull(state.info?.model, state.info?.firmware?.let { "FW $it" })
        .joinToString("  ·  ")
        .ifBlank { fallbackSubtitle.orEmpty() }

    Card(colors = gtCardColors(), border = BorderStroke(1.dp, extras.cardStroke), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Watch, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = extras.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                BatteryReadout(state.battery)
            }

            StatusPill(label = linkLabel(state), color = tint)

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            when (state.link) {
                LinkState.IDLE -> Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                LinkState.CONNECTED -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                else -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun BatteryReadout(level: Int?) {
    val extras = LocalGtExtras.current
    val low = level != null && level < 20
    val color = if (low) extras.batteryLow else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (low) Icons.Rounded.BatteryAlert else Icons.Rounded.BatteryFull,
                contentDescription = "Battery",
                tint = if (level == null) extras.metadata else color,
                modifier = Modifier.size(22.dp),
            )
            Text(
                if (level == null) "—" else "$level%",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = MetricNumber.fontFamily),
                color = if (level == null) extras.metadata else color,
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatAgo(now: Long, then: Long): String {
    val minutes = ((now - then) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

@Composable
private fun ActivityCard(activity: ActivityTotals?, goal: Int, now: Long, canRefresh: Boolean, onRefresh: () -> Unit) {
    val extras = LocalGtExtras.current
    val ints = remember { NumberFormat.getIntegerInstance() }
    val steps = activity?.steps
    val progress = if (steps != null && goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f

    Card(colors = gtCardColors(), border = BorderStroke(1.dp, extras.cardStroke), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TODAY", style = MaterialTheme.typography.labelMedium, color = extras.metadata)
                    Text(
                        if (activity != null) "Updated ${formatAgo(now, activity.updatedAt)}" else "Not synced yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.metadata,
                    )
                }
                IconButton(onClick = onRefresh, enabled = canRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh activity")
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (steps != null) ints.format(steps.toLong()) else "—",
                        style = MetricNumber.copy(fontSize = 48.sp, lineHeight = 52.sp),
                        color = extras.steps,
                    )
                    Text(
                        "steps",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = extras.steps,
                    trackColor = extras.steps.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    if (steps != null && goal > 0) "${(progress * 100).toInt()}% of ${ints.format(goal.toLong())} step goal"
                    else "Goal ${ints.format(goal.toLong())} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(
                        value = activity?.calories?.let { ints.format(it.toLong()) } ?: "—",
                        unit = "kcal",
                        color = extras.calories,
                        modifier = Modifier.weight(1f),
                    )
                    Stat(
                        value = activity?.let { String.format(Locale.getDefault(), "%.2f", it.distanceMeters / 1000.0) } ?: "—",
                        unit = "km",
                        color = extras.distance,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(color.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = MetricNumber.copy(fontSize = 26.sp, lineHeight = 30.sp), color = color, maxLines = 1)
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, LocalGtExtras.current.cardStroke),
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else content,
                modifier = Modifier.size(24.dp),
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = content, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun RowScope.FeatureTile(feature: Feature, onClick: () -> Unit) {
    val dim = if (feature.enabled) 1f else 0.38f
    Surface(
        onClick = onClick,
        enabled = feature.enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, LocalGtExtras.current.cardStroke),
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Column(Modifier.heightIn(min = 112.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = dim)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    feature.icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = dim),
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    feature.title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    feature.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WatchSection(connected: Boolean, onSwitch: () -> Unit, onForget: () -> Unit, onReset: () -> Unit) {
    Card(
        colors = gtCardColors(),
        border = BorderStroke(1.dp, LocalGtExtras.current.cardStroke),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            WatchActionRow(Icons.Rounded.SwapHoriz, "Switch watch", "Pair a different watch", enabled = true, destructive = false, onClick = onSwitch)
            HorizontalDivider(color = LocalGtExtras.current.cardStroke)
            WatchActionRow(Icons.Rounded.LinkOff, "Forget this watch", "Remove it and its pairing key from this phone", enabled = true, destructive = false, onClick = onForget)
            HorizontalDivider(color = LocalGtExtras.current.cardStroke)
            WatchActionRow(
                Icons.Rounded.RestartAlt, "Factory reset watch",
                if (connected) "Erases everything on the watch" else "Connect first to reset the watch",
                enabled = connected, destructive = true, onClick = onReset,
            )
        }
    }
}

@Composable
private fun WatchActionRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, destructive: Boolean, onClick: () -> Unit) {
    val base = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val color = base.copy(alpha = if (enabled) 1f else 0.38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f))
        }
    }
}
