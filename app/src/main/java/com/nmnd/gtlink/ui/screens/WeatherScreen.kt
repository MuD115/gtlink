@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.ui.AppViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private fun relativeTime(then: Long, now: Long): String {
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 24 * 3600 -> "${s / 3600} h ago"
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(then))
    }
}

@Composable
fun WeatherScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val results by vm.citySearch.collectAsStateWithLifecycle()

    var locationGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        if (granted) vm.chooseCity(null)
    }

    var cityMode by remember { mutableStateOf(!s.weatherUseDeviceLocation) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 2) {
            delay(400)
            vm.searchCity(q)
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(topBar = { GtTopBar("Weather", onBack, busy = busy) }) { inner ->
        ScreenColumn(inner) {
            SectionHeader("Weather on the watch")
            SettingsCard {
                SwitchRow("Send weather", "Keep the watch's weather app up to date", s.weatherEnabled) { v ->
                    vm.updateSettings { it.copy(weatherEnabled = v) }
                }
                SwitchRow("Fahrenheit", "Show temperatures in °F", s.fahrenheit) { v ->
                    vm.updateSettings { it.copy(fahrenheit = v) }
                }
            }

            SectionHeader("Location")
            SettingsCard {
                LocationOption(
                    title = "Use phone location",
                    subtitle = "Follows you as you move",
                    selected = !cityMode,
                    enabled = s.weatherEnabled,
                ) {
                    cityMode = false
                    if (locationGranted) vm.chooseCity(null)
                    else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                LocationOption(
                    title = "Choose a city",
                    subtitle = if (s.weatherUseDeviceLocation || s.weatherCityName == null) "Search for a city below" else s.weatherCityName,
                    selected = cityMode,
                    enabled = s.weatherEnabled,
                ) { cityMode = true }
            }

            if (!cityMode && !locationGranted && s.weatherEnabled) {
                InfoBanner("Location access is needed to use your phone's position.")
                TextButton(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Text("Allow location")
                }
            }

            if (cityMode && s.weatherEnabled) {
                Spacer(Modifier.height(12.dp))
                GtSearchField(query, { query = it }, "Search for a city")
                if (!s.weatherUseDeviceLocation && s.weatherCityName != null) {
                    Text(
                        "Current choice: ${s.weatherCityName}",
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (query.trim().length >= 2 && results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SettingsCard {
                        results.forEach { city ->
                            ClickRow(
                                title = city.name,
                                subtitle = listOfNotNull(city.admin, city.country).joinToString(", ").ifEmpty { null },
                            ) {
                                vm.chooseCity(city)
                                query = ""
                            }
                        }
                    }
                }
            }

            SectionHeader("Update")
            SettingsCard {
                SettingRow(
                    title = "Last sent",
                    subtitle = watch.lastWeatherSync?.let { relativeTime(it, now) } ?: "Not sent yet",
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.sendWeatherNow() },
                enabled = watch.connected && s.weatherEnabled && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send weather now") }
            Text(
                "Weather data comes from Open-Meteo and is refreshed every 30 minutes while the watch is connected.",
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        enabled = enabled,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
