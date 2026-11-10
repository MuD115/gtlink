@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nmnd.gtlink.theme.LocalGtExtras
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Scaffolding
// ---------------------------------------------------------------------------------------

@Composable
fun GtTopBar(
    title: String,
    onBack: () -> Unit,
    busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = actions,
        )
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Scrolling column that respects the Scaffold's insets while letting content pass under the bars. */
@Composable
fun ScreenColumn(
    inner: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dir = LocalLayoutDirection.current
    Column(
        modifier
            .fillMaxSize()
            .padding(top = inner.calculateTopPadding())
            .verticalScroll(rememberScrollState())
            .padding(
                start = inner.calculateStartPadding(dir) + 16.dp,
                end = inner.calculateEndPadding(dir) + 16.dp,
            ),
    ) {
        content()
        Spacer(Modifier.height(inner.calculateBottomPadding() + 32.dp))
    }
}

/** Content padding for a LazyColumn that fills the Scaffold body. */
@Composable
fun listPadding(inner: PaddingValues, top: Dp = 8.dp, extraBottom: Dp = 32.dp): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = inner.calculateStartPadding(dir) + 16.dp,
        top = inner.calculateTopPadding() + top,
        end = inner.calculateEndPadding(dir) + 16.dp,
        bottom = inner.calculateBottomPadding() + extraBottom,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, LocalGtExtras.current.cardStroke),
    ) {
        content()
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun GtSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled,
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun ClickRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        enabled = enabled,
    ) {
        if (value != null) {
            Text(
                value,
                modifier = Modifier.widthIn(max = 170.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun formatMinutes(minutes: Int): String {
    val m = minutes.coerceIn(0, 24 * 60 - 1)
    return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
}

@Composable
fun TimeRow(
    title: String,
    minutes: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ClickRow(title, value = formatMinutes(minutes), enabled = enabled) { open = true }
    if (open) {
        GtTimePickerDialog(
            title = title,
            initialMinutes = minutes,
            onConfirm = { open = false; onChange(it) },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun GtTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = initialMinutes.coerceIn(0, 24 * 60 - 1)
    val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.width(IntrinsicSize.Min),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
                }
            }
        }
    }
}

val DayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DayLetters = listOf("M", "T", "W", "T", "F", "S", "S")

/** Monday = bit 0 ... Sunday = bit 6. */
fun daysSummary(mask: Int): String {
    val m = mask and 0x7F
    return when (m) {
        0 -> "Once"
        0x7F -> "Every day"
        0x1F -> "Weekdays"
        0x60 -> "Weekends"
        else -> DayNames.filterIndexed { i, _ -> (m and (1 shl i)) != 0 }.joinToString(", ")
    }
}

@Composable
fun DaysRow(
    mask: Int,
    enabled: Boolean = true,
    title: String? = "Repeat",
    onChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    daysSummary(mask),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayLetters.forEachIndexed { i, letter ->
                val on = (mask and (1 shl i)) != 0
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { onChange(mask xor (1 shl i)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Snaps to [step] increments between range.first and range.last; with [allowOff] a leading
 * "Off" stop (value 0) is added. The change is committed when the finger lifts.
 */
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    unit: String = "",
    allowOff: Boolean = false,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val options = remember(range, step, allowOff) {
        buildList {
            if (allowOff) add(0)
            var v = range.first
            while (v <= range.last) { add(v); v += step }
        }
    }
    val committed = options.indices.minByOrNull { abs(options[it] - value) } ?: 0
    var index by remember(committed) { mutableIntStateOf(committed) }
    val shown = options[index.coerceIn(options.indices)]
    val text = when {
        allowOff && shown == 0 -> "Off"
        unit.isEmpty() -> String.format(Locale.US, "%,d", shown)
        else -> String.format(Locale.US, "%,d", shown) + " " + unit
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { index = it.roundToInt().coerceIn(options.indices) },
            valueRange = 0f..(options.size - 1).coerceAtLeast(1).toFloat(),
            enabled = enabled,
            onValueChangeFinished = {
                val v = options[index.coerceIn(options.indices)]
                if (v != value) onChange(v)
            },
        )
    }
}

@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    ClickRow(title, value = label, enabled = enabled) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (v, l) ->
                        val isSelected = v == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = isSelected, role = Role.RadioButton) {
                                    open = false
                                    onSelect(v)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Text(l, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun GtDatePickerDialog(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val thisYear = LocalDate.now().year
    val start = when {
        initial.year < 1920 -> LocalDate.of(1920, 1, 1)
        initial.year > thisYear -> LocalDate.now()
        else -> initial
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1920..thisYear,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
