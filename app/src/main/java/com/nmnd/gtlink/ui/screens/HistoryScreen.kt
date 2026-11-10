@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.data.DayTotals
import com.nmnd.gtlink.data.TimedValue
import com.nmnd.gtlink.link.Commands
import com.nmnd.gtlink.theme.LocalGtExtras
import com.nmnd.gtlink.theme.MetricNumber
import com.nmnd.gtlink.ui.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.time.format.TextStyle as DayTextStyle

private const val MAX_EXPORT_CHARS = 150_000
private const val GAP_SECONDS = 30 * 60

private data class DayDetail(
    val heart: List<TimedValue> = emptyList(),
    val spo2: List<TimedValue> = emptyList(),
    val resting: Int? = null,
    val sleep: List<Commands.SleepSegment> = emptyList(),
)

private fun syncAgo(then: Long, now: Long): String {
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 24 * 3600 -> "${s / 3600} h ago"
        else -> "${s / (24 * 3600)} d ago"
    }
}

private fun formatDuration(seconds: Long): String {
    val m = (seconds / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@Composable
fun HistoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val sync = watch.historySync

    var days by rememberSaveable { mutableIntStateOf(7) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    var totals by remember { mutableStateOf<List<DayTotals>>(emptyList()) }
    LaunchedEffect(days, sync.lastSync) { totals = vm.dailyTotals(days) }

    val date = LocalDate.now(zone).minusDays(dayOffset.toLong())
    var detail by remember { mutableStateOf(DayDetail()) }
    val dayFrom = date.atStartOfDay(zone).toEpochSecond()
    val dayTo = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val sleepFrom = date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
    val sleepTo = date.atTime(12, 0).atZone(zone).toEpochSecond()
    LaunchedEffect(dayOffset, sync.lastSync) {
        detail = DayDetail(
            heart = vm.heartRate(dayFrom, dayTo),
            spo2 = vm.spo2(dayFrom, dayTo),
            resting = vm.restingHeartRate(dayFrom, dayTo).lastOrNull()?.value,
            sleep = vm.sleep(sleepFrom, sleepTo),
        )
    }

    Scaffold(
        topBar = {
            GtTopBar("Health history", onBack, busy = sync.running) {
                IconButton(onClick = {
                    scope.launch {
                        val csv = trimCsv(vm.exportHistoryCsv())
                        if (csv.lines().count { it.isNotBlank() } <= 1) {
                            Toast.makeText(context, "Nothing to export yet", Toast.LENGTH_SHORT).show()
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "GT Link history")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            context.startActivity(Intent.createChooser(send, "Export history"))
                        }
                    }
                }) { Icon(Icons.Rounded.Share, contentDescription = "Export CSV") }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear history")
                }
            }
        },
    ) { inner ->
        ScreenColumn(inner) {
            SyncCard(
                running = sync.running,
                percent = sync.percent,
                lastSync = sync.lastSync,
                lastResult = sync.lastResult,
                now = now,
                canSync = watch.connected && !sync.running,
                onSync = { vm.syncHistory() },
            )
            if (!watch.connected) InfoBanner("Connect the watch to download new data. Saved history is shown below.")

            SectionHeader("Steps")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text("7 days") })
                FilterChip(selected = days == 30, onClick = { days = 30 }, label = { Text("30 days") })
            }
            Spacer(Modifier.height(12.dp))
            StepsCard(totals, settings.stepGoal, settings.metric)

            SectionHeader("Day")
            DaySelector(
                date = date,
                offset = dayOffset,
                onPrev = { dayOffset += 1 },
                onNext = { if (dayOffset > 0) dayOffset -= 1 },
            )

            SectionHeader("Heart rate")
            HeartCard(detail, dayFrom, dayTo - dayFrom)

            SectionHeader("Blood oxygen")
            Spo2Card(detail.spo2, dayFrom, dayTo - dayFrom)

            SectionHeader("Sleep")
            SleepCard(detail.sleep, sleepFrom, sleepTo, zone)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This deletes the health data stored on this phone. The watch keeps its own copy, so a sync can bring it back.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun trimCsv(csv: String): String {
    if (csv.length <= MAX_EXPORT_CHARS) return csv
    val header = csv.substringBefore('\n')
    val tail = csv.substring(csv.length - MAX_EXPORT_CHARS)
    return header + "\n" + tail.substringAfter('\n')
}

// ---------------------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------------------

@Composable
private fun SyncCard(
    running: Boolean,
    percent: Int,
    lastSync: Long?,
    lastResult: String?,
    now: Long,
    canSync: Boolean,
    onSync: () -> Unit,
) {
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Syncing $percent%" else if (lastSync != null) "Synced ${syncAgo(lastSync, now)}" else "Not synced yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!running && lastResult != null) {
                        Text(
                            lastResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = onSync, enabled = canSync) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Sync now")
                }
            }
            if (running) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------------------

@Composable
private fun StepsCard(totals: List<DayTotals>, goal: Int, metric: Boolean) {
    val withData = totals.filter { it.steps > 0 }
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            if (withData.isEmpty()) {
                EmptyChart(160.dp)
            } else {
                StepsBarChart(totals, goal)
                Spacer(Modifier.height(16.dp))
                val avg = withData.sumOf { it.steps } / withData.size
                val meters = totals.sumOf { it.distanceMeters }
                val distance = if (metric) meters / 1000.0 else meters / 1609.344
                Row {
                    Stat("Avg steps", "%,d".format(Locale.US, avg), LocalGtExtras.current.steps, Modifier.weight(1f))
                    Stat(
                        if (metric) "Distance km" else "Distance mi",
                        String.format(Locale.US, "%.1f", distance),
                        LocalGtExtras.current.distance,
                        Modifier.weight(1f),
                    )
                    Stat(
                        "Calories kcal",
                        "%,d".format(Locale.US, totals.sumOf { it.calories }),
                        LocalGtExtras.current.calories,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsBarChart(totals: List<DayTotals>, goal: Int) {
    val color = LocalGtExtras.current.steps
    val axis = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = goalColor)
    val n = totals.size
    val labels = remember(totals) {
        totals.mapIndexed { i, t ->
            when {
                n <= 7 -> t.day.dayOfWeek.getDisplayName(DayTextStyle.SHORT, Locale.getDefault())
                (n - 1 - i) % 7 == 0 -> t.day.dayOfMonth.toString() + "/" + t.day.monthValue
                else -> null
            }
        }
    }

    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val labelH = 24.dp.toPx()
        val topPad = 16.dp.toPx()
        val plotH = size.height - labelH - topPad
        val baseline = topPad + plotH
        val maxV = max(max(totals.maxOf { it.steps }, goal), 1) * 1.05f
        fun yOf(v: Float) = baseline - plotH * (v / maxV)

        drawLine(axis, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())

        val slot = size.width / n
        val barW = slot * if (n <= 7) 0.56f else 0.64f
        val radius = CornerRadius(min(barW / 3f, 6.dp.toPx()))
        totals.forEachIndexed { i, t ->
            if (t.steps <= 0) return@forEachIndexed
            val top = yOf(t.steps.toFloat())
            drawRoundRect(
                color = if (t.steps >= goal) color else color.copy(alpha = 0.55f),
                topLeft = Offset(i * slot + (slot - barW) / 2f, top),
                size = Size(barW, baseline - top),
                cornerRadius = radius,
            )
        }

        if (goal > 0) {
            val gy = yOf(goal.toFloat())
            drawLine(
                goalColor.copy(alpha = 0.7f), Offset(0f, gy), Offset(size.width, gy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            val m = measurer.measure("Goal " + "%,d".format(Locale.US, goal), labelStyle)
            drawText(m, topLeft = Offset(size.width - m.size.width, max(0f, gy - m.size.height - 1.dp.toPx())))
        }

        labels.forEachIndexed { i, text ->
            if (text == null) return@forEachIndexed
            val m = measurer.measure(text, labelStyle)
            val x = (i * slot + slot / 2f - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            drawText(m, topLeft = Offset(x, baseline + 6.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------------------
// Day detail
// ---------------------------------------------------------------------------------------

@Composable
private fun DaySelector(date: LocalDate, offset: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val label = when (offset) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    SettingsCard {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous day") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (offset in 0..1) {
                    Text(
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onNext, enabled = offset > 0) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next day")
            }
        }
    }
}

@Composable
private fun HeartCard(detail: DayDetail, fromSec: Long, spanSec: Long) {
    val color = LocalGtExtras.current.heart
    val values = detail.heart.map { it.value }
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            if (values.isEmpty()) {
                EmptyChart(180.dp)
            } else {
                val lo = (values.min() / 10) * 10 - 10
                val hi = ((values.max() + 9) / 10) * 10 + 10
                LineChart(detail.heart, fromSec, spanSec, max(lo, 0), hi, color, 180.dp)
                Spacer(Modifier.height(16.dp))
                Row {
                    Stat("Min", values.min().toString(), color, Modifier.weight(1f))
                    Stat("Avg", values.average().roundToInt().toString(), color, Modifier.weight(1f))
                    Stat("Max", values.max().toString(), color, Modifier.weight(1f))
                    Stat("Resting", detail.resting?.toString() ?: "–", color, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Spo2Card(points: List<TimedValue>, fromSec: Long, spanSec: Long) {
    val color = LocalGtExtras.current.distance
    val values = points.map { it.value }
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            if (values.isEmpty()) {
                EmptyChart(100.dp)
            } else {
                val lo = ((min(values.min(), 95) - 1) / 5) * 5
                LineChart(points, fromSec, spanSec, lo, 100, color, 120.dp)
                Spacer(Modifier.height(16.dp))
                Row {
                    Stat("Min %", values.min().toString(), color, Modifier.weight(1f))
                    Stat("Avg %", values.average().roundToInt().toString(), color, Modifier.weight(1f))
                    Stat("Max %", values.max().toString(), color, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LineChart(
    points: List<TimedValue>,
    fromSec: Long,
    spanSec: Long,
    yMin: Int,
    yMax: Int,
    color: Color,
    height: Dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val left = 34.dp.toPx()
        val top = 6.dp.toPx()
        val labelH = 22.dp.toPx()
        val plotW = size.width - left - 4.dp.toPx()
        val plotH = size.height - top - labelH
        val baseline = top + plotH
        val range = (yMax - yMin).coerceAtLeast(1).toFloat()
        fun yOf(v: Int) = baseline - plotH * ((v - yMin) / range)
        fun xOf(t: Int) = left + plotW * ((t - fromSec).toFloat() / spanSec.toFloat()).coerceIn(0f, 1f)

        listOf(yMin, (yMin + yMax) / 2, yMax).distinct().forEach { v ->
            val y = yOf(v)
            drawLine(grid.copy(alpha = if (v == yMin) 1f else 0.6f), Offset(left, y), Offset(left + plotW, y), 1.dp.toPx())
            val m = measurer.measure(v.toString(), labelStyle)
            drawText(m, topLeft = Offset(left - 6.dp.toPx() - m.size.width, y - m.size.height / 2f))
        }
        for (hour in listOf(0, 6, 12, 18)) {
            val x = left + plotW * (hour * 3600f / spanSec)
            val m = measurer.measure("%02d:00".format(Locale.US, hour), labelStyle)
            drawText(m, topLeft = Offset(x.coerceAtMost(size.width - m.size.width), baseline + 5.dp.toPx()))
        }

        val path = Path()
        var prev: Int? = null
        points.forEach { p ->
            val x = xOf(p.timeSec)
            val y = yOf(p.value.coerceIn(yMin, yMax))
            if (prev == null || p.timeSec - prev!! > GAP_SECONDS) path.moveTo(x, y) else path.lineTo(x, y)
            prev = p.timeSec
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (points.size <= 120) {
            points.forEach { p -> drawCircle(color, 2.5.dp.toPx(), Offset(xOf(p.timeSec), yOf(p.value.coerceIn(yMin, yMax)))) }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Sleep
// ---------------------------------------------------------------------------------------

@Composable
private fun SleepCard(raw: List<Commands.SleepSegment>, windowFrom: Long, windowTo: Long, zone: ZoneId) {
    val segments = remember(raw, windowFrom, windowTo) {
        raw.map {
            Commands.SleepSegment(max(it.startSec.toLong(), windowFrom).toInt(), min(it.endSec.toLong(), windowTo).toInt(), it.type)
        }.filter { it.endSec > it.startSec }
    }
    val deepColor = LocalGtExtras.current.distance
    val lightColor = deepColor.copy(alpha = 0.45f)

    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            if (segments.isEmpty()) {
                EmptyChart(100.dp)
            } else {
                val start = segments.minOf { it.startSec }
                val end = segments.maxOf { it.endSec }
                val deepSec = segments.filter { it.deep }.sumOf { (it.endSec - it.startSec).toLong() }
                val lightSec = segments.filter { !it.deep }.sumOf { (it.endSec - it.startSec).toLong() }
                val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                fun clock(sec: Int) = Instant.ofEpochSecond(sec.toLong()).atZone(zone).format(timeFmt)

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    val span = (end - start).toFloat()
                    segments.forEach { seg ->
                        val x0 = size.width * (seg.startSec - start) / span
                        val x1 = size.width * (seg.endSec - start) / span
                        val h = if (seg.deep) size.height else size.height * 0.55f
                        drawRect(
                            color = if (seg.deep) deepColor else lightColor,
                            topLeft = Offset(x0, size.height - h),
                            size = Size(max(x1 - x0, 1f), h),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(clock(start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(clock(end), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    Stat("Total", formatDuration(deepSec + lightSec), MaterialTheme.colorScheme.onSurface, Modifier.weight(1.2f), small = true)
                    Stat("Deep", formatDuration(deepSec), deepColor, Modifier.weight(1f), small = true)
                    Stat("Light", formatDuration(lightSec), lightColor, Modifier.weight(1f), small = true)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Bedtime", clock(start), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f), small = true)
                    Stat("Woke up", clock(end), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f), small = true)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (small) MaterialTheme.typography.titleMedium.copy(fontFamily = MetricNumber.fontFamily)
            else MetricNumber.copy(fontSize = 22.sp, lineHeight = 26.sp),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyChart(height: Dp) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(
            "No data yet — sync from the watch",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
