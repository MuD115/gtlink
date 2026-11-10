package com.nmnd.gtlink.data

import com.nmnd.gtlink.link.Commands
import com.nmnd.gtlink.protocol.Packet
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Every watch setting the app controls. The phone is the source of truth: the watch
 * can't report most of these back, so they're stored here and pushed on connect.
 * Times are minutes since midnight; day masks have Monday in bit 0.
 */
@Serializable
data class WatchSettings(
    // Display & wear
    val liftToWake: Boolean = true,
    val rotateToNavigate: Boolean = false,
    val wearOnLeft: Boolean = true,
    val use24Hour: Boolean = true,
    /** 1 = y/m/d, 2 = m/d/y, 3 = d/m/y */
    val dateFormat: Int = 3,
    /** "auto" follows the phone, otherwise a tag like "en-GB". */
    val language: String = "auto",
    val metric: Boolean = true,
    val fahrenheit: Boolean = false,

    // Notifications
    val notificationsEnabled: Boolean = true,
    val suppressWhenNotWorn: Boolean = false,
    val disconnectAlert: Boolean = true,
    val forwardCalls: Boolean = true,
    /** Package names never forwarded to the watch. */
    val blockedApps: Set<String> = emptySet(),
    val skipOngoing: Boolean = true,

    // Do not disturb
    val dndEnabled: Boolean = false,
    val dndStart: Int = 22 * 60,
    val dndEnd: Int = 7 * 60,
    val dndDays: Int = 0x7F,

    // Health monitoring
    val continuousHeartRate: Boolean = true,
    val highHeartRateAlert: Int = 0,
    val lowHeartRateAlert: Int = 0,
    val continuousSpo2: Boolean = false,
    val lowSpo2Alert: Int = 0,
    val truSleep: Boolean = true,
    val stressMonitoring: Boolean = false,
    val skinTemperature: Boolean = false,

    // Activity
    val stepGoal: Int = 8000,
    val inactivityReminder: Boolean = false,
    val inactivityInterval: Int = 60,
    val inactivityStart: Int = 8 * 60,
    val inactivityEnd: Int = 22 * 60,
    val inactivityDays: Int = 0x1F,

    // Profile
    val heightCm: Int = 175,
    val weightKg: Int = 70,
    val birthYear: Int = 1990,
    val birthMonth: Int = 1,
    val birthDay: Int = 1,
    /** 1 male, 2 female, 3 unspecified */
    val gender: Int = 3,

    // Phone-side features
    val findPhoneEnabled: Boolean = true,
    val musicControl: Boolean = true,
    val weatherEnabled: Boolean = true,
    val weatherUseDeviceLocation: Boolean = true,
    val weatherCityName: String? = null,
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    val autoReconnect: Boolean = true,
    val cameraRemote: Boolean = true,
    val notificationReplies: Boolean = true,
    /** Keep the lists in step: dismissing on the phone clears it on the watch. */
    val syncDismissals: Boolean = true,
    val autoSyncHistory: Boolean = true,

    /**
     * The Huawei account ID ("huid") the watch was set up with in Huawei Health. Sending it
     * lets this app pair without a factory reset. Empty = not used.
     */
    val huaweiAccountId: String = "",
)

/**
 * Turns settings into packets. Grouped so a single toggle only resends its own group,
 * while a fresh connection sends everything.
 */
object SettingsSync {
    enum class Group { DISPLAY, FORMAT, LOCALE, NOTIFICATIONS, DND, HEART, SPO2, SLEEP, STRESS, TEMPERATURE, ACTIVITY, PROFILE }

    fun packets(s: WatchSettings, group: Group): List<Packet> = when (group) {
        Group.DISPLAY -> listOf(
            Commands.activateOnLift(s.liftToWake),
            Commands.navigateOnRotate(s.rotateToNavigate),
            Commands.wearLocation(s.wearOnLeft),
        )
        Group.FORMAT -> listOf(Commands.dateFormat(s.dateFormat, if (s.use24Hour) 2 else 1))
        Group.LOCALE -> listOf(
            Commands.language(localeTag(s.language), s.metric),
            Commands.temperatureUnit(s.fahrenheit),
        )
        Group.NOTIFICATIONS -> listOf(
            Commands.notificationsEnabled(s.notificationsEnabled),
            Commands.pushWhenNotWorn(s.suppressWhenNotWorn),
            Commands.disconnectAlert(s.disconnectAlert),
        )
        Group.DND -> listOf(
            Commands.dndDelete(),
            Commands.dndAdd(s.dndEnabled, s.dndStart, s.dndEnd, s.dndDays),
        )
        Group.HEART -> listOf(
            Commands.continuousHeartRate(s.continuousHeartRate),
            Commands.highHeartRateAlert(s.highHeartRateAlert),
            Commands.lowHeartRateAlert(s.lowHeartRateAlert),
        )
        Group.SPO2 -> listOf(
            Commands.continuousSpo2(s.continuousSpo2),
            Commands.lowSpo2Alert(s.lowSpo2Alert),
        )
        Group.SLEEP -> listOf(Commands.truSleep(s.truSleep))
        Group.STRESS -> listOf(Commands.automaticStress(s.stressMonitoring))
        Group.TEMPERATURE -> listOf(Commands.skinTemperature(s.skinTemperature))
        Group.ACTIVITY -> listOf(
            Commands.stepGoal(s.stepGoal),
            Commands.inactivityReminder(s.inactivityReminder, s.inactivityInterval, s.inactivityStart, s.inactivityEnd, s.inactivityDays),
        )
        Group.PROFILE -> listOf(
            Commands.userInfo(s.heightCm, s.weightKg, age(s), s.birthYear, s.birthMonth, s.birthDay, s.gender),
        )
    }

    fun all(s: WatchSettings): List<Packet> = Group.entries.flatMap { packets(s, it) }

    /** The groups whose packets differ between two settings snapshots. */
    fun changedGroups(old: WatchSettings, new: WatchSettings): List<Group> =
        Group.entries.filter { g ->
            packets(old, g).map { it.toString() } != packets(new, g).map { it.toString() }
        }

    fun localeTag(setting: String): String {
        if (setting != "auto") return setting.replace('_', '-')
        val l = Locale.getDefault()
        val country = l.country.ifEmpty { l.language }
        return l.language + "-" + country.uppercase(Locale.ROOT)
    }

    private fun age(s: WatchSettings): Int {
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - s.birthYear
        val beforeBirthday = (now.get(java.util.Calendar.MONTH) + 1) * 100 + now.get(java.util.Calendar.DAY_OF_MONTH) <
            s.birthMonth * 100 + s.birthDay
        if (beforeBirthday) age--
        return age.coerceIn(1, 120)
    }
}
