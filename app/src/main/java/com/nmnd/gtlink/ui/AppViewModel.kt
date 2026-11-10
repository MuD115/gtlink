package com.nmnd.gtlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nmnd.gtlink.GtApp
import com.nmnd.gtlink.data.DayTotals
import com.nmnd.gtlink.data.SavedWatch
import com.nmnd.gtlink.data.TimedValue
import com.nmnd.gtlink.data.SmartAlarm
import com.nmnd.gtlink.data.WatchSettings
import com.nmnd.gtlink.link.Commands
import com.nmnd.gtlink.link.WatchAlarm
import com.nmnd.gtlink.link.UploadState
import com.nmnd.gtlink.link.WatchFace
import com.nmnd.gtlink.link.WatchPackages
import com.nmnd.gtlink.service.WatchService
import com.nmnd.gtlink.service.WeatherSync
import com.nmnd.gtlink.weather.City
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Bluetooth device as the picker shows it. */
data class BtDevice(val address: String, val name: String, val bonded: Boolean) {
    /** Huawei watches advertise as "HUAWEI WATCH GT 4-XXX". */
    val looksLikeWatch: Boolean get() = name.contains("watch", ignoreCase = true) || name.contains("huawei", ignoreCase = true)
}

data class AppEntry(val packageName: String, val label: String)

data class PhoneContact(val name: String, val number: String)

/**
 * The single ViewModel behind every screen. Screens read state flows and call these
 * actions; nothing in the UI touches the connection or storage directly. Outcomes
 * that deserve a snackbar arrive on [messages].
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GtApp
    private val connection = app.connection
    private val store = app.store

    val watchState = connection.state
    val savedWatch: StateFlow<SavedWatch?> = store.watch
    val settings: StateFlow<WatchSettings> = store.settings
    val alarms: StateFlow<List<WatchAlarm>> = store.alarms
    val smartAlarm: StateFlow<SmartAlarm> = store.smartAlarm
    val logLines: StateFlow<List<String>> = app.log.lines

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _scanResults = MutableStateFlow<List<BtDevice>>(emptyList())
    val scanResults: StateFlow<List<BtDevice>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _citySearch = MutableStateFlow<List<City>>(emptyList())
    val citySearch: StateFlow<List<City>> = _citySearch

    private val _busy = MutableStateFlow(false)
    /** True while a user-triggered watch operation runs; screens show progress. */
    val busy: StateFlow<Boolean> = _busy

    private fun say(text: String) { _messages.tryEmit(text) }

    private fun op(success: String?, block: suspend () -> Result<*>) {
        viewModelScope.launch {
            _busy.value = true
            val r = block()
            _busy.value = false
            r.onSuccess { if (success != null) say(success) }
                .onFailure { say(it.message ?: "Something went wrong") }
        }
    }

    // --- pairing & connection ---------------------------------------------------------

    private val bluetooth: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val bluetoothEnabled: Boolean get() = bluetooth?.isEnabled == true

    /** Already-paired devices, watches first. Requires BLUETOOTH_CONNECT on Android 12+. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BtDevice> = runCatching {
        bluetooth?.bondedDevices.orEmpty().map { BtDevice(it.address, it.name ?: it.address, true) }
            .sortedByDescending { it.looksLikeWatch }
    }.getOrDefault(emptyList())

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val name = runCatching { d.name }.getOrNull() ?: return
                    if (_scanResults.value.none { it.address == d.address }) {
                        _scanResults.value = (_scanResults.value + BtDevice(d.address, name, d.bondState == BluetoothDevice.BOND_BONDED))
                            .sortedByDescending { it.looksLikeWatch }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }
    }
    private var receiverRegistered = false

    /** Classic discovery for watches not yet paired with the phone. Requires BLUETOOTH_SCAN. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetooth ?: return say("This phone has no Bluetooth")
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                app, discoveryReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        _scanResults.value = emptyList()
        runCatching {
            adapter.cancelDiscovery()
            _scanning.value = adapter.startDiscovery()
        }.onFailure { say("Couldn't scan: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { bluetooth?.cancelDiscovery() }
        _scanning.value = false
    }

    /** Remembers [device] as the watch and starts the background connection. */
    fun selectWatch(device: BtDevice) {
        stopScan()
        store.setWatch(SavedWatch(device.address, device.name))
        connection.disconnect()
        WatchService.start(app)
    }

    fun connect() {
        if (savedWatch.value == null) return
        WatchService.start(app)
        connection.connect()
    }

    fun disconnect() {
        WatchService.stop(app)
        connection.disconnect()
    }

    /** Drops the watch and its pairing key; the user will pair again from scratch. */
    fun forgetWatch() {
        connection.forgetPairing()
        WatchService.stop(app)
        store.setWatch(null)
    }

    // --- dashboard ---------------------------------------------------------------------

    fun refresh() = op(null) {
        connection.refreshBattery()
        connection.refreshActivity()
    }

    fun syncTime() = op("Time synced") { connection.syncTime() }

    fun sendTestNotification() = op("Sent") {
        connection.sendNotification(Commands.NotificationType.GENERIC, "GT Link", "GT Link", "Notifications reach your watch.", app.packageName)
    }

    fun factoryReset() = op("Factory reset sent") {
        connection.factoryReset().also { if (it.isSuccess) forgetWatch() }
    }

    // --- settings ----------------------------------------------------------------------

    /** Saves the change and sends only the affected settings to the watch. */
    fun updateSettings(transform: (WatchSettings) -> WatchSettings) {
        val (old, new) = store.updateSettings(transform)
        if (watchState.value.connected) {
            viewModelScope.launch { connection.applySettings(old, new) }
        }
    }

    fun pushAllSettings() = op("Settings sent to watch") { connection.pushAllSettings() }

    // --- alarms ------------------------------------------------------------------------

    /** The watch holds up to 5 event alarms. */
    fun setAlarms(alarms: List<WatchAlarm>) {
        store.setAlarms(alarms.take(5))
        if (watchState.value.connected) op(null) { connection.pushAlarms() }
    }

    fun setSmartAlarm(alarm: SmartAlarm) {
        store.setSmartAlarm(alarm)
        if (watchState.value.connected) op(null) { connection.pushAlarms() }
    }

    fun readAlarmsFromWatch() = op(null) {
        connection.readAlarms().onSuccess { store.setAlarms(it); say("Loaded ${it.size} alarms from the watch") }
    }

    // --- watch faces -------------------------------------------------------------------

    fun refreshWatchfaces() = op(null) { connection.refreshWatchfaces() }
    fun setWatchface(face: WatchFace) = op("Watch face changed") { connection.setWatchface(face) }
    fun deleteWatchface(face: WatchFace) = op("Watch face removed") { connection.deleteWatchface(face) }

    // --- contacts ----------------------------------------------------------------------

    /** Phone contacts with a number. Requires READ_CONTACTS. */
    suspend fun loadPhoneContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PhoneContact>()
        runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    out += PhoneContact(name, number)
                }
            }
        }
        out.distinctBy { it.name + it.number.filter { ch -> ch.isDigit() } }
    }

    fun syncContacts(contacts: List<PhoneContact>) = viewModelScope.launch {
        _busy.value = true
        connection.syncContacts(contacts.map { it.name to it.number })
            .onSuccess { say("Wrote $it contacts to the watch") }
            .onFailure { say(it.message ?: "Contacts sync failed") }
        _busy.value = false
    }

    // --- weather -----------------------------------------------------------------------

    fun searchCity(query: String) = viewModelScope.launch {
        _citySearch.value = runCatching { app.weather.searchCity(query) }.getOrElse {
            say(it.message ?: "Search failed")
            emptyList()
        }
    }

    fun chooseCity(city: City?) {
        _citySearch.value = emptyList()
        updateSettings {
            if (city == null) it.copy(weatherUseDeviceLocation = true, weatherCityName = null, weatherLat = null, weatherLon = null)
            else it.copy(weatherUseDeviceLocation = false, weatherCityName = city.name, weatherLat = city.latitude, weatherLon = city.longitude)
        }
    }

    fun sendWeatherNow() = op("Weather sent") { WeatherSync.run(app) }

    // --- notifications filter ------------------------------------------------------------

    /** Launchable apps, for the per-app notification switch list. */
    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != app.packageName }
            .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    fun setAppBlocked(packageName: String, blocked: Boolean) = updateSettings {
        it.copy(blockedApps = if (blocked) it.blockedApps + packageName else it.blockedApps - packageName)
    }

    // --- installing: watch faces, apps, music --------------------------------------------

    val uploadState: StateFlow<UploadState> = connection.uploader.state

    /**
     * Reads a file the user picked (SAF uri), works out what it is, checks a watch face
     * fits the screen, and starts sending it. Progress is on [uploadState].
     */
    fun install(uri: Uri) = viewModelScope.launch {
        val resolver = app.contentResolver
        val prepared = runCatching {
            withContext(Dispatchers.IO) {
                var name = "file"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
                val mime = resolver.getType(uri)
                var title: String? = null
                var artist: String? = null
                if (mime?.startsWith("audio/") == true) {
                    val mmr = MediaMetadataRetriever()
                    runCatching {
                        mmr.setDataSource(app, uri)
                        title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    }
                    mmr.release()
                }
                WatchPackages.prepare(name, bytes, mime, title, artist)
            }
        }.getOrElse { return@launch say(it.message ?: "Couldn't open that file") }

        val st = watchState.value
        val screen = prepared.screen
        if (screen != null && st.screenWidth != null && st.screenHeight != null) {
            val dims = screen.split('*', 'x', 'X').mapNotNull { it.trim().toIntOrNull() }
            if (dims.size == 2 && (dims[0] != st.screenWidth || dims[1] != st.screenHeight)) {
                return@launch say("This face is made for $screen; your watch is ${st.screenWidth}×${st.screenHeight}")
            }
        }
        connection.upload(prepared).onFailure { say(it.message ?: "Upload failed") }
    }

    fun cancelUpload() = connection.uploader.cancel()

    // --- history -------------------------------------------------------------------------

    fun syncHistory() = viewModelScope.launch {
        connection.syncHistory()
            .onSuccess { say(if (it == 0) "Already up to date" else "Synced $it minutes of activity") }
            .onFailure { say(it.message ?: "History sync failed") }
    }

    private val history get() = connection.history

    suspend fun dailyTotals(days: Int): List<DayTotals> = withContext(Dispatchers.IO) { history.dailyTotals(days) }
    suspend fun heartRate(fromSec: Long, toSec: Long): List<TimedValue> = withContext(Dispatchers.IO) { history.heartRate(fromSec, toSec) }
    suspend fun spo2(fromSec: Long, toSec: Long): List<TimedValue> = withContext(Dispatchers.IO) { history.spo2(fromSec, toSec) }
    suspend fun restingHeartRate(fromSec: Long, toSec: Long): List<TimedValue> = withContext(Dispatchers.IO) { history.restingHeartRate(fromSec, toSec) }
    suspend fun sleep(fromSec: Long, toSec: Long): List<Commands.SleepSegment> = withContext(Dispatchers.IO) { history.sleep(fromSec, toSec) }
    suspend fun exportHistoryCsv(): String = withContext(Dispatchers.IO) { history.exportCsv() }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { history.clear(); say("History cleared") }

    // --- camera remote --------------------------------------------------------------------

    /** The camera screen calls this on resume/pause so the watch shows or hides its shutter. */
    fun cameraVisible(open: Boolean) = viewModelScope.launch { connection.setCameraOpen(open) }

    // --- pairing without reset -------------------------------------------------------------

    /** The Huawei account ID; applied on the next connection. */
    fun setHuaweiAccountId(id: String) = updateSettings { it.copy(huaweiAccountId = id.filter { c -> c.isDigit() }) }

    // --- developer console -------------------------------------------------------------

    fun clearLog() = app.log.clear()

    fun sendRaw(service: Int, command: Int, tlvHex: String, encrypt: Boolean) = op(null) {
        connection.sendRaw(service, command, tlvHex, encrypt).onSuccess { reply ->
            say(if (reply == null) "No reply" else "Reply: $reply")
        }
    }

    override fun onCleared() {
        if (receiverRegistered) runCatching { app.unregisterReceiver(discoveryReceiver) }
        super.onCleared()
    }
}
