package com.nmnd.gtlink.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nmnd.gtlink.theme.LocalGtExtras

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Permissions the pairing flow needs: connect + scan on Android 12+, location for discovery before that. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Everything needed to pair: connect and scan on Android 12+, fine location below. */
fun hasBluetoothPermission(context: Context): Boolean =
    bluetoothPermissions().all { context.granted(it) }

/** Enough to open the socket to an already-paired watch. */
fun canConnectBluetooth(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.granted(Manifest.permission.BLUETOOTH_CONNECT)

private val callPermissions = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.READ_CONTACTS,
)

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.granted(Manifest.permission.POST_NOTIFICATIONS)

private fun listenerGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun batteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private class PermRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val required: Boolean,
    val granted: Boolean,
    val actionLabel: String,
    val permissions: Array<String>? = null,
    val intent: (() -> Intent)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    // Rows whose runtime request came back denied; the system won't ask again, so offer app settings.
    var blockedKeys by remember { mutableStateOf(emptySet<String>()) }
    var pendingKey by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val key = pendingKey
        if (key != null && result.values.any { !it }) blockedKeys = blockedKeys + key
        tick++
    }

    val rows = remember(tick) {
        listOf(
            PermRow(
                key = "bluetooth",
                icon = Icons.Rounded.Bluetooth,
                title = "Bluetooth",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Lets GT Link find and talk to your watch (nearby devices)."
                } else {
                    "Android needs location access before an app can look for Bluetooth devices."
                },
                required = true,
                granted = hasBluetoothPermission(context),
                actionLabel = "Allow",
                permissions = bluetoothPermissions(),
            ),
            PermRow(
                key = "post",
                icon = Icons.Rounded.Notifications,
                title = "Show notifications",
                description = "Needed for the \"connected\" status notification that keeps the link alive in the background.",
                required = false,
                granted = notificationsGranted(context),
                actionLabel = "Allow",
                permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else null,
            ),
            PermRow(
                key = "listener",
                icon = Icons.Rounded.NotificationsActive,
                title = "Notification access",
                description = "Required to forward phone notifications to the watch, and to control music playing on this phone.",
                required = false,
                granted = listenerGranted(context),
                actionLabel = "Open settings",
                intent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            ),
            PermRow(
                key = "calls",
                icon = Icons.Rounded.Call,
                title = "Calls and contacts",
                description = "Shows caller names on the watch and lets you answer or reject calls from your wrist.",
                required = false,
                granted = callPermissions.all { context.granted(it) },
                actionLabel = "Allow",
                permissions = callPermissions,
            ),
            PermRow(
                key = "location",
                icon = Icons.Rounded.LocationOn,
                title = "Location for weather",
                description = "Approximate location so the watch shows weather for where you are. You can pick a city instead.",
                required = false,
                granted = context.granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                actionLabel = "Allow",
                permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
            PermRow(
                key = "camera",
                icon = Icons.Rounded.PhotoCamera,
                title = "Camera",
                description = "Lets the watch act as a remote shutter: GT Link opens the camera on this phone and takes the photo when you press the button on your wrist.",
                required = false,
                granted = context.granted(Manifest.permission.CAMERA),
                actionLabel = "Allow",
                permissions = arrayOf(Manifest.permission.CAMERA),
            ),
            PermRow(
                key = "battery",
                icon = Icons.Rounded.BatteryChargingFull,
                title = "Unrestricted battery",
                description = "Without this Android may stop GT Link in the background and the connection to the watch drops.",
                required = false,
                granted = batteryExempt(context),
                actionLabel = "Open settings",
                intent = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
            ),
        )
    }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }

    fun act(row: PermRow) {
        val perms = row.permissions
        when {
            perms != null && row.key in blockedKeys -> openAppSettings()
            perms != null -> {
                pendingKey = row.key
                launcher.launch(perms)
            }
            row.intent != null -> runCatching { context.startActivity(row.intent.invoke()) }
        }
    }

    val doneCount = rows.count { it.granted }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Permissions", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        "$doneCount of ${rows.size} ready",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Only Bluetooth is required. The rest switch on individual features, and you can come back here any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                PermissionCard(row, blocked = row.key in blockedKeys, onAction = { act(row) })
            }
        }
    }
}

@Composable
private fun PermissionCard(row: PermRow, blocked: Boolean, onAction: () -> Unit) {
    val extras = LocalGtExtras.current
    val tint = if (row.granted) extras.connected else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, extras.cardStroke),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        if (row.required) {
                            Text("REQUIRED", style = MaterialTheme.typography.labelSmall, color = extras.metadata)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        row.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (row.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (row.granted) "Granted" else "Not granted",
                    tint = tint,
                )
            }
            if (!row.granted) {
                val label = if (blocked) "Open app settings" else row.actionLabel
                if (row.required) {
                    Button(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(label) }
                } else {
                    FilledTonalButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(label) }
                }
            } else if (row.intent != null) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text("Manage") }
            }
        }
    }
}
