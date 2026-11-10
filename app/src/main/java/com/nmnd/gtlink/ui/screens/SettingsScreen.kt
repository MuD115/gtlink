@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmnd.gtlink.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.data.WatchSettings
import com.nmnd.gtlink.ui.AppViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DateFormats = listOf(1 to "Year / Month / Day", 2 to "Month / Day / Year", 3 to "Day / Month / Year")

private val Languages = listOf(
    "auto" to "Follow phone",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "ar-SA" to "Arabic",
    "de-DE" to "German",
    "fr-FR" to "French",
    "es-ES" to "Spanish",
    "it-IT" to "Italian",
    "tr-TR" to "Turkish",
    "ru-RU" to "Russian",
    "zh-CN" to "Chinese (Simplified)",
)

private val Genders = listOf(1 to "Male", 2 to "Female", 3 to "Prefer not to say")

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watchState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    fun update(transform: (WatchSettings) -> WatchSettings) = vm.updateSettings(transform)

    var pickBirthday by remember { mutableStateOf(false) }
    val birthday = runCatching { LocalDate.of(s.birthYear, s.birthMonth, s.birthDay) }
        .getOrDefault(LocalDate.of(1990, 1, 1))

    Scaffold(topBar = { GtTopBar("Watch settings", onBack, busy = busy) }) { inner ->
        ScreenColumn(inner) {
            if (!watch.connected) {
                InfoBanner("The watch isn't connected. Changes are saved and sent when the watch connects.")
            }

            SectionHeader("Display & wear")
            SettingsCard {
                SwitchRow("Raise to wake", "Light the screen when you lift your wrist", s.liftToWake) { v ->
                    update { it.copy(liftToWake = v) }
                }
                SwitchRow("Rotate crown to navigate", "Use the crown to scroll and move between screens", s.rotateToNavigate) { v ->
                    update { it.copy(rotateToNavigate = v) }
                }
                ChoiceRow(
                    title = "Worn on",
                    options = listOf(true to "Left wrist", false to "Right wrist"),
                    selected = s.wearOnLeft,
                ) { v -> update { it.copy(wearOnLeft = v) } }
            }

            SectionHeader("Time & language")
            SettingsCard {
                SwitchRow("24-hour time", null, s.use24Hour) { v -> update { it.copy(use24Hour = v) } }
                ChoiceRow("Date format", DateFormats, s.dateFormat) { v -> update { it.copy(dateFormat = v) } }
                ChoiceRow("Watch language", Languages, s.language) { v -> update { it.copy(language = v) } }
                SwitchRow("Metric units", "Kilometres and kilograms instead of miles and pounds", s.metric) { v ->
                    update { it.copy(metric = v) }
                }
                SwitchRow("Fahrenheit", "Show temperatures in °F", s.fahrenheit) { v ->
                    update { it.copy(fahrenheit = v) }
                }
            }

            SectionHeader("Do not disturb")
            SettingsCard {
                SwitchRow("Do not disturb", "Silence the watch on a schedule", s.dndEnabled) { v ->
                    update { it.copy(dndEnabled = v) }
                }
                TimeRow("Starts", s.dndStart, enabled = s.dndEnabled) { v -> update { it.copy(dndStart = v) } }
                TimeRow("Ends", s.dndEnd, enabled = s.dndEnabled) { v -> update { it.copy(dndEnd = v) } }
                DaysRow(s.dndDays, enabled = s.dndEnabled) { v -> update { it.copy(dndDays = v) } }
            }

            SectionHeader("Health monitoring")
            SettingsCard {
                SwitchRow("Continuous heart rate", "Measure all day, not only during workouts", s.continuousHeartRate) { v ->
                    update { it.copy(continuousHeartRate = v) }
                }
                SliderRow(
                    "High heart rate alert", s.highHeartRateAlert, 100..150, step = 5, unit = "bpm",
                    allowOff = true, enabled = s.continuousHeartRate,
                ) { v -> update { it.copy(highHeartRateAlert = v) } }
                SliderRow(
                    "Low heart rate alert", s.lowHeartRateAlert, 40..60, step = 5, unit = "bpm",
                    allowOff = true, enabled = s.continuousHeartRate,
                ) { v -> update { it.copy(lowHeartRateAlert = v) } }
                SwitchRow("Continuous blood oxygen", "Measure SpO2 automatically", s.continuousSpo2) { v ->
                    update { it.copy(continuousSpo2 = v) }
                }
                SliderRow(
                    "Low SpO2 alert", s.lowSpo2Alert, 85..95, unit = "%",
                    allowOff = true, enabled = s.continuousSpo2,
                ) { v -> update { it.copy(lowSpo2Alert = v) } }
                SwitchRow("TruSleep", "Track sleep stages and breathing", s.truSleep) { v ->
                    update { it.copy(truSleep = v) }
                }
                SwitchRow("Stress monitoring", "Measure stress automatically", s.stressMonitoring) { v ->
                    update { it.copy(stressMonitoring = v) }
                }
                SwitchRow("Skin temperature", "Track wrist skin temperature", s.skinTemperature) { v ->
                    update { it.copy(skinTemperature = v) }
                }
            }

            SectionHeader("Activity")
            SettingsCard {
                SliderRow("Daily step goal", s.stepGoal, 1000..30000, step = 500, unit = "steps") { v ->
                    update { it.copy(stepGoal = v) }
                }
                SwitchRow("Inactivity reminder", "Nudge you to move after sitting for a while", s.inactivityReminder) { v ->
                    update { it.copy(inactivityReminder = v) }
                }
                SliderRow(
                    "Remind every", s.inactivityInterval, 30..180, step = 15, unit = "min",
                    enabled = s.inactivityReminder,
                ) { v -> update { it.copy(inactivityInterval = v) } }
                TimeRow("From", s.inactivityStart, enabled = s.inactivityReminder) { v ->
                    update { it.copy(inactivityStart = v) }
                }
                TimeRow("Until", s.inactivityEnd, enabled = s.inactivityReminder) { v ->
                    update { it.copy(inactivityEnd = v) }
                }
                DaysRow(s.inactivityDays, enabled = s.inactivityReminder) { v ->
                    update { it.copy(inactivityDays = v) }
                }
            }

            SectionHeader("Profile")
            SettingsCard {
                SliderRow("Height", s.heightCm, 120..220, unit = "cm") { v -> update { it.copy(heightCm = v) } }
                SliderRow("Weight", s.weightKg, 30..200, unit = "kg") { v -> update { it.copy(weightKg = v) } }
                ClickRow("Birthday", value = birthday.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) {
                    pickBirthday = true
                }
                ChoiceRow("Gender", Genders, s.gender) { v -> update { it.copy(gender = v) } }
            }

            SectionHeader("Phone features")
            SettingsCard {
                SwitchRow("Find my phone", "Ring this phone from the watch", s.findPhoneEnabled) { v ->
                    update { it.copy(findPhoneEnabled = v) }
                }
                SwitchRow("Music control", "Control playback from the watch", s.musicControl) { v ->
                    update { it.copy(musicControl = v) }
                }
                SwitchRow("Reconnect automatically", "Keep trying when the link drops", s.autoReconnect) { v ->
                    update { it.copy(autoReconnect = v) }
                }
                SwitchRow("Camera remote", "The watch's camera app becomes a shutter", s.cameraRemote) { v ->
                    update { it.copy(cameraRemote = v) }
                }
                SwitchRow("Reply from the watch", "Answer notifications with replies sent from your phone", s.notificationReplies) { v ->
                    update { it.copy(notificationReplies = v) }
                }
                SwitchRow("Sync dismissals", "Clear on the watch when dismissed on the phone", s.syncDismissals) { v ->
                    update { it.copy(syncDismissals = v) }
                }
                SwitchRow("Sync health history automatically", "Download steps, heart rate and sleep in the background", s.autoSyncHistory) { v ->
                    update { it.copy(autoSyncHistory = v) }
                }
            }

            Spacer(Modifier.height(24.dp))
            FilledTonalButton(
                onClick = { vm.pushAllSettings() },
                enabled = watch.connected && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resend all settings to watch") }
        }
    }

    if (pickBirthday) {
        GtDatePickerDialog(
            initial = birthday,
            onConfirm = { d ->
                pickBirthday = false
                update { it.copy(birthYear = d.year, birthMonth = d.monthValue, birthDay = d.dayOfMonth) }
            },
            onDismiss = { pickBirthday = false },
        )
    }
}
