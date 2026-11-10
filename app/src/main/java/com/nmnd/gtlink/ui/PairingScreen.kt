package com.nmnd.gtlink.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.theme.LocalGtExtras

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(vm: AppViewModel, onPaired: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    val hasPermission = remember(tick) { hasBluetoothPermission(context) }
    val bluetoothOn = remember(tick) { vm.bluetoothEnabled }
    val ready = hasPermission && bluetoothOn

    val bonded = remember(tick, ready) { if (ready) vm.bondedDevices() else emptyList() }
    val nearby = remember(bonded, scanResults) { scanResults.filter { r -> bonded.none { it.address == r.address } } }

    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    fun choose(device: BtDevice) {
        vm.selectWatch(device)
        onPaired()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Choose your watch", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    }
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
            item { InstructionsCard() }
            item { NoResetCard(initialId = settings.huaweiAccountId, onIdChange = vm::setHuaweiAccountId) }

            if (!hasPermission) {
                item {
                    NoticeCard(
                        icon = Icons.Rounded.Bluetooth,
                        title = "Bluetooth permission needed",
                        body = "GT Link needs to see nearby Bluetooth devices to find your watch and connect to it.",
                        button = "Allow",
                        onClick = { permissionLauncher.launch(bluetoothPermissions()) },
                    )
                }
            } else if (!bluetoothOn) {
                item {
                    NoticeCard(
                        icon = Icons.Rounded.BluetoothDisabled,
                        title = "Bluetooth is off",
                        body = "Turn Bluetooth on to see your watch.",
                        button = "Turn on",
                        onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    )
                }
            } else {
                item { SectionLabel("Paired with this phone") }
                if (bonded.isEmpty()) {
                    item {
                        Text(
                            "No paired devices yet. Scan below to find your watch.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                items(bonded, key = { "b-" + it.address }) { d -> DeviceRow(d, onClick = { choose(d) }) }

                item { Spacer(Modifier.height(4.dp)) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { SectionLabel("Nearby") }
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            OutlinedButton(onClick = vm::stopScan) { Text("Stop") }
                        } else {
                            Button(onClick = vm::startScan) {
                                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Scan for watches")
                            }
                        }
                    }
                }
                if (nearby.isEmpty()) {
                    item {
                        Text(
                            if (scanning) "Looking for devices..." else "Scan to list unpaired devices in range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                items(nearby, key = { "n-" + it.address }) { d -> DeviceRow(d, onClick = { choose(d) }) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalGtExtras.current.metadata,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun InstructionsCard() {
    val extras = LocalGtExtras.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, extras.cardStroke),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you pair", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If your GT 4 is paired with another phone or Huawei Health, reset it first " +
                        "(Settings › System › Reset on the watch).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Keep it close and unlocked. When pairing starts, confirm the request on the watch screen. " +
                        "Android may also ask to pair over Bluetooth — accept it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NoResetCard(initialId: String, onIdChange: (String) -> Unit) {
    val extras = LocalGtExtras.current
    var expanded by rememberSaveable { mutableStateOf(initialId.isNotEmpty()) }
    var text by rememberSaveable { mutableStateOf(initialId) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, extras.cardStroke),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Pair without resetting the watch", style = MaterialTheme.typography.titleMedium)
                    if (!expanded && text.isNotEmpty()) {
                        Text("Account ID set", style = MaterialTheme.typography.labelMedium, color = extras.connected)
                    }
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = extras.metadata,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "If the watch is still set up with Huawei Health, you can skip the factory reset by entering the " +
                            "Huawei account ID it was set up with. Remove Huawei Health (or revoke its Bluetooth permission) " +
                            "first, and don't unpair inside Huawei Health.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val digits = text.length
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            val clean = v.filter { it.isDigit() }.take(32)
                            text = clean
                            onIdChange(clean)
                        },
                        label = { Text("Huawei account ID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(
                                when {
                                    digits == 0 -> "Optional. Leave empty to pair the usual way."
                                    digits in 15..19 -> "$digits digits"
                                    else -> "$digits digits. IDs usually have 15–19, so double-check it."
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("How to find it", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FindStep(
                            "A",
                            "On a computer, sign in at cloud.huawei.com, open the browser developer tools " +
                                "(Application or Storage, then Cookies) and copy the userId value.",
                        )
                        FindStep(
                            "B",
                            "On a rooted phone, read old_user_id in " +
                                "/data/data/com.huawei.health/shared_prefs/login_data.xml.",
                        )
                        FindStep("C", "With adb, run adb logcat | grep huid= while Huawei Health is open.")
                    }
                    Text(
                        "This method is documented by the Gadgetbridge community and may not work on every firmware. " +
                            "If pairing fails, reset the watch and pair normally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindStep(tag: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tag,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, LocalGtExtras.current.cardStroke),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(button) }
        }
    }
}

@Composable
private fun DeviceRow(device: BtDevice, onClick: () -> Unit) {
    val extras = LocalGtExtras.current
    val likely = device.looksLikeWatch
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (likely) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(1.dp, if (likely) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else extras.cardStroke),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (likely) Icons.Rounded.Watch else Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = if (likely) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, style = MaterialTheme.typography.labelMedium, color = extras.metadata)
                if (likely) {
                    Text("Looks like your watch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = extras.metadata)
        }
    }
}
