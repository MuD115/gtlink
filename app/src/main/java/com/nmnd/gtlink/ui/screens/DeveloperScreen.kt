@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.ui.AppViewModel

private fun parseHexByte(text: String): Int? =
    text.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..255 }

private fun isHexString(text: String): Boolean =
    text.length % 2 == 0 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard(modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun InfoLine(key: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "-", Modifier.weight(0.62f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun DeveloperScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by vm.logLines.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var showInfo by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    var serviceText by remember { mutableStateOf("") }
    var commandText by remember { mutableStateOf("") }
    var tlvText by remember { mutableStateOf("") }
    var encrypt by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            GtTopBar("Developer", onBack, busy = busy) {
                IconButton(
                    onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "GT Link protocol log")
                                putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                            }
                            context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    enabled = lines.isNotEmpty(),
                ) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
                IconButton(onClick = { vm.clearLog() }, enabled = lines.isNotEmpty()) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CollapsibleCard("Link info", showInfo, { showInfo = !showInfo }) {
                    InfoLine("Link", watch.link.name)
                    InfoLine("Status", watch.status.ifBlank { null })
                    InfoLine("Error", watch.error)
                    InfoLine("Services", "${watch.services.size}" + if (watch.services.isNotEmpty()) "  " + watch.services.sorted().joinToString(" ") { "%02X".format(it) } else "")
                    InfoLine("Commands", watch.commands.values.sumOf { it.size }.toString())
                    InfoLine("Name", watch.info?.name)
                    InfoLine("Model", watch.info?.model)
                    InfoLine("Firmware", watch.info?.firmware)
                    InfoLine("Hardware", watch.info?.hardware)
                    InfoLine("Serial", watch.info?.serial)
                    InfoLine("Battery", watch.battery?.let { "$it%" })
                    InfoLine("Contact slots", watch.contactSlots?.toString())
                    Spacer(Modifier.height(8.dp))
                }

                CollapsibleCard("Send raw command", showRaw, { showRaw = !showRaw }) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HexField("Service", serviceText, { serviceText = it; error = null }, Modifier.weight(1f))
                            HexField("Command", commandText, { commandText = it; error = null }, Modifier.weight(1f))
                        }
                        HexField("TLV (hex)", tlvText, { tlvText = it; error = null }, Modifier.fillMaxWidth(), singleLine = false)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Encrypt", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = encrypt, onCheckedChange = { encrypt = it })
                        }
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = {
                                val service = parseHexByte(serviceText)
                                val command = parseHexByte(commandText)
                                val tlv = tlvText.filter { !it.isWhitespace() }
                                error = when {
                                    service == null -> "Service must be a hex byte (00-FF)"
                                    command == null -> "Command must be a hex byte (00-FF)"
                                    !isHexString(tlv) -> "TLV must be an even number of hex digits"
                                    else -> null
                                }
                                if (error == null && service != null && command != null) vm.sendRaw(service, command, tlv, encrypt)
                            },
                            enabled = watch.connected && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (watch.connected) "Send" else "Watch not connected") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Protocol log", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("${lines.size} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "The log can contain pairing data. Share it only with people you trust.",
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsCard(modifier = Modifier.weight(1f).padding(bottom = 12.dp)) {
                if (lines.isEmpty()) {
                    Text(
                        "Nothing logged yet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                line,
                                modifier = Modifier.padding(vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HexField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
    )
}
