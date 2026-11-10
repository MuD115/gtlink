@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.link.WatchAlarm
import com.nmnd.gtlink.ui.AppViewModel

private const val MaxAlarms = 5
private const val MaxNameLength = 20

private val SmartWindows = listOf(10 to "10 minutes", 20 to "20 minutes", 30 to "30 minutes")

/** Which alarm the dialog edits: an index, or [NewAlarm] for a fresh one. */
private const val NewAlarm = -1

@Composable
fun AlarmsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val smart by vm.smartAlarm.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Int?>(null) }
    val full = alarms.size >= MaxAlarms

    Scaffold(
        topBar = {
            GtTopBar("Alarms", onBack, busy = busy) {
                IconButton(onClick = { vm.readAlarmsFromWatch() }, enabled = watch.connected && !busy) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "Load from watch")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!full) editing = NewAlarm },
                containerColor = if (full) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = if (full) "Alarm limit reached" else "Add alarm")
            }
        },
    ) { inner ->
        ScreenColumn(inner) {
            if (!watch.connected) {
                InfoBanner("The watch isn't connected. Alarm changes are saved and sent when it connects.")
            }

            SectionHeader("Alarms (${alarms.size} of $MaxAlarms)")
            if (alarms.isEmpty()) {
                SettingsCard {
                    Text(
                        "No alarms yet. Tap + to add one.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            alarms.forEachIndexed { index, alarm ->
                SettingsCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = index }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val tint = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatMinutes(alarm.hour * 60 + alarm.minute),
                                style = MaterialTheme.typography.headlineMedium,
                                color = tint,
                            )
                            Text(
                                listOf(alarm.name.trim(), daysSummary(alarm.daysMask)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = alarm.enabled,
                            onCheckedChange = { on -> vm.setAlarms(alarms.replaceAt(index, alarm.copy(enabled = on))) },
                        )
                    }
                }
            }

            SectionHeader("Smart alarm")
            SettingsCard {
                SwitchRow(
                    "Smart alarm",
                    "Wakes you during light sleep, shortly before the set time",
                    smart.enabled,
                ) { on -> vm.setSmartAlarm(smart.copy(enabled = on)) }
                TimeRow("Wake by", smart.hour * 60 + smart.minute, enabled = smart.enabled) { m ->
                    vm.setSmartAlarm(smart.copy(hour = m / 60, minute = m % 60))
                }
                ChoiceRow("Window", SmartWindows, smart.windowMinutes, enabled = smart.enabled) { w ->
                    vm.setSmartAlarm(smart.copy(windowMinutes = w))
                }
                DaysRow(smart.daysMask, enabled = smart.enabled) { m -> vm.setSmartAlarm(smart.copy(daysMask = m)) }
            }
            Spacer(Modifier.height(64.dp))
        }
    }

    editing?.let { index ->
        val existing = alarms.getOrNull(index)
        if (index == NewAlarm || existing != null) {
            AlarmEditDialog(
                initial = existing ?: WatchAlarm(),
                isNew = existing == null,
                onSave = { saved ->
                    editing = null
                    vm.setAlarms(if (existing == null) alarms + saved else alarms.replaceAt(index, saved))
                },
                onDelete = {
                    editing = null
                    vm.setAlarms(alarms.filterIndexed { i, _ -> i != index })
                },
                onDismiss = { editing = null },
            )
        }
    }
}

private fun List<WatchAlarm>.replaceAt(index: Int, alarm: WatchAlarm): List<WatchAlarm> =
    mapIndexed { i, a -> if (i == index) alarm else a }

@Composable
private fun AlarmEditDialog(
    initial: WatchAlarm,
    isNew: Boolean,
    onSave: (WatchAlarm) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableStateOf(initial.hour * 60 + initial.minute) }
    var name by remember { mutableStateOf(initial.name) }
    var days by remember { mutableStateOf(initial.daysMask) }
    var pickTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New alarm" else "Edit alarm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedButton(
                    onClick = { pickTime = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(formatMinutes(minutes), style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MaxNameLength) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                    supportingText = { Text("${name.length}/$MaxNameLength") },
                )
                Spacer(Modifier.height(4.dp))
                DaysRow(days) { days = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(WatchAlarm(enabled = initial.enabled || isNew, hour = minutes / 60, minute = minutes % 60, daysMask = days, name = name.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickTime) {
        GtTimePickerDialog(
            title = "Alarm time",
            initialMinutes = minutes,
            onConfirm = { minutes = it; pickTime = false },
            onDismiss = { pickTime = false },
        )
    }
}
