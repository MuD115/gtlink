package com.nmnd.gtlink.data

import android.content.Context
import android.content.SharedPreferences
import com.nmnd.gtlink.link.PairingStore
import com.nmnd.gtlink.link.WatchAlarm
import com.nmnd.gtlink.protocol.Crypto
import com.nmnd.gtlink.protocol.hexToBytes
import com.nmnd.gtlink.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Random

@Serializable
data class SavedWatch(val address: String, val name: String)

@Serializable
data class SmartAlarm(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val daysMask: Int = 0x1F,
    /** How early the watch may wake you when it sees light sleep. */
    val windowMinutes: Int = 20,
)

@Serializable
private data class AlarmDto(val enabled: Boolean, val hour: Int, val minute: Int, val daysMask: Int, val name: String)

/**
 * Everything the app keeps on the phone, in one SharedPreferences file. Pairing keys
 * live here too, which is why backup excludes shared preferences entirely.
 */
class AppStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("gtlink", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _watch = MutableStateFlow(loadWatch())
    val watch: StateFlow<SavedWatch?> = _watch

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<WatchSettings> = _settings

    private val _alarms = MutableStateFlow(loadAlarms())
    val alarms: StateFlow<List<WatchAlarm>> = _alarms

    private val _smartAlarm = MutableStateFlow(
        prefs.getString("smart_alarm", null)?.let { runCatching { json.decodeFromString<SmartAlarm>(it) }.getOrNull() } ?: SmartAlarm(),
    )
    val smartAlarm: StateFlow<SmartAlarm> = _smartAlarm

    private fun loadWatch(): SavedWatch? =
        prefs.getString("watch", null)?.let { runCatching { json.decodeFromString<SavedWatch>(it) }.getOrNull() }

    private fun loadSettings(): WatchSettings =
        prefs.getString("settings", null)?.let { runCatching { json.decodeFromString<WatchSettings>(it) }.getOrNull() } ?: WatchSettings()

    private fun loadAlarms(): List<WatchAlarm> =
        prefs.getString("alarms", null)?.let { s ->
            runCatching { json.decodeFromString<List<AlarmDto>>(s).map { WatchAlarm(it.enabled, it.hour, it.minute, it.daysMask, it.name) } }.getOrNull()
        } ?: emptyList()

    fun setWatch(watch: SavedWatch?) {
        prefs.edit().apply {
            if (watch == null) remove("watch") else putString("watch", json.encodeToString(SavedWatch.serializer(), watch))
        }.apply()
        _watch.value = watch
    }

    fun updateSettings(transform: (WatchSettings) -> WatchSettings): Pair<WatchSettings, WatchSettings> {
        val old = _settings.value
        val new = transform(old)
        prefs.edit().putString("settings", json.encodeToString(WatchSettings.serializer(), new)).apply()
        _settings.value = new
        return old to new
    }

    fun setAlarms(alarms: List<WatchAlarm>) {
        val dto = alarms.map { AlarmDto(it.enabled, it.hour, it.minute, it.daysMask, it.name) }
        prefs.edit().putString("alarms", json.encodeToString(dto)).apply()
        _alarms.value = alarms
    }

    fun setSmartAlarm(alarm: SmartAlarm) {
        prefs.edit().putString("smart_alarm", json.encodeToString(SmartAlarm.serializer(), alarm)).apply()
        _smartAlarm.value = alarm
    }

    /** Per-watch pairing material, keyed by the watch's Bluetooth address. */
    fun pairing(address: String): PairingStore = object : PairingStore {
        private val prefix = "pair_${address.replace(":", "")}_"

        override var authKey: ByteArray?
            get() = prefs.getString(prefix + "key", null)?.hexToBytes()
            set(value) {
                prefs.edit().apply {
                    if (value == null) remove(prefix + "key") else putString(prefix + "key", value.toHex())
                }.commit()
            }

        override val androidId: String
            get() = prefs.getString(prefix + "id", null) ?: Crypto.randomBytes(16).toHex().also {
                prefs.edit().putString(prefix + "id", it).commit()
            }

        override val fakeMac: String
            get() = prefs.getString(prefix + "mac", null) ?: buildString {
                append("FF:FF:FF")
                val r = Random()
                repeat(3) { append(":%02X".format(r.nextInt(255))) }
            }.also { prefs.edit().putString(prefix + "mac", it).commit() }
    }

    fun forgetPairing(address: String) {
        val prefix = "pair_${address.replace(":", "")}_"
        prefs.edit().remove(prefix + "key").remove(prefix + "id").remove(prefix + "mac").apply()
    }
}
