@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.ui.AppViewModel
import com.nmnd.gtlink.ui.PhoneContact

@Composable
fun ContactsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var contacts by remember { mutableStateOf<List<PhoneContact>?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<PhoneContact>()) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) contacts = vm.loadPhoneContacts()
    }

    val slots = watch.contactSlots
    val full = slots != null && selected.size >= slots

    val filtered = remember(contacts, query) {
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        contacts.orEmpty().filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) || (digits.isNotEmpty() && it.number.filter { c -> c.isDigit() }.contains(digits))
        }
    }

    Scaffold(
        topBar = { GtTopBar("Contacts", onBack, busy = busy) },
        bottomBar = {
            if (hasPermission) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { vm.syncContacts(selected) },
                        enabled = watch.connected && selected.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) {
                        Text(if (watch.connected) "Write to watch" else "Connect the watch to write")
                    }
                }
            }
        },
    ) { inner ->
        if (!hasPermission) {
            ScreenColumn(inner) {
                SectionHeader("Access needed")
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("GT Link reads your contacts only to pick the ones you want on the watch's favourite contacts list.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts access") }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("Open app settings") }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = listPadding(inner)) {
            item {
                Column {
                    InfoBanner("Writing replaces the favourite contacts list on the watch with the contacts you select here.")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val limit = if (slots != null) "$slots slots" else "the watch's limit"
                        Text(
                            "Selected ${selected.size} of $limit",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (selected.isNotEmpty()) TextButton(onClick = { selected = emptyList() }) { Text("Clear") }
                    }
                    Spacer(Modifier.height(8.dp))
                    GtSearchField(query, { query = it }, "Search contacts")
                    Spacer(Modifier.height(12.dp))
                }
            }
            when {
                contacts == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                filtered.isEmpty() -> item {
                    Text(
                        if (contacts.orEmpty().isEmpty()) "No contacts with a phone number were found." else "No matches.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(filtered) { contact ->
                    val isSelected = contact in selected
                    val canToggle = isSelected || !full
                    val toggle = {
                        selected = if (isSelected) selected - contact else if (canToggle) selected + contact else selected
                    }
                    SettingsCard(shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(bottom = 6.dp).alpha(if (canToggle) 1f else 0.45f)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canToggle, onClick = toggle)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null, enabled = canToggle, modifier = Modifier.padding(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    contact.number,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
