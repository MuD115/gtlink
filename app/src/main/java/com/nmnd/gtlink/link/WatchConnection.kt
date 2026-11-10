package com.nmnd.gtlink.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.nmnd.gtlink.data.AppStore
import com.nmnd.gtlink.data.SettingsSync
import com.nmnd.gtlink.data.WatchSettings
import com.nmnd.gtlink.protocol.LinkParams
import com.nmnd.gtlink.protocol.Packet
import com.nmnd.gtlink.protocol.Tlv
import com.nmnd.gtlink.protocol.hexToBytes
import com.nmnd.gtlink.weather.HuaweiWeatherIcons
import com.nmnd.gtlink.weather.WeatherReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

enum class LinkState { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, WAITING_TO_RETRY }

data class WatchInfo(
    val name: String?,
    val model: String?,
    val firmware: String?,
    val hardware: String?,
    val serial: String?,
)

data class ActivityTotals(val steps: Int, val calories: Int, val distanceMeters: Int, val updatedAt: Long)

data class WatchState(
    val link: LinkState = LinkState.IDLE,
    val status: String = "",
    val error: String? = null,
    val info: WatchInfo? = null,
    val battery: Int? = null,
    val activity: ActivityTotals? = null,
    /** Service ids the watch reported; empty until connected. */
    val services: Set<Int> = emptySet(),
    val commands: Map<Int, Set<Int>> = emptyMap(),
    val watchFaces: List<WatchFace> = emptyList(),
    val contactSlots: Int? = null,
    val lastWeatherSync: Long? = null,
    /** Optional-feature bitmap from the watch (expand capabilities). */
    val capabilities: ByteArray? = null,
    /** e.g. 466 x 466, for checking a watch face fits. */
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val historySync: HistorySyncState = HistorySyncState(),
) {
    val connected get() = link == LinkState.CONNECTED

    /** True when the watch reported the command, or when it never told us (assume yes). */
    fun supports(service: Int, command: Int): Boolean {
        val list = commands[service] ?: return commands.isEmpty() || service in services
        return command in list
    }

    fun hasCapability(which: Int) = Commands.hasExpandCapability(capabilities, which)
    val canReplyToNotifications get() = hasCapability(Commands.Capability.NOTIFICATION_REPLY)
    val canCameraRemote get() = supports(0x01, 0x29)
}

data class HistorySyncState(
    val running: Boolean = false,
    /** 0..100 while running. */
    val percent: Int = 0,
    val lastSync: Long? = null,
    val lastResult: String? = null,
)

/** Things the watch asks the phone to do. */
sealed interface WatchEvent {
    data class FindPhone(val start: Boolean) : WatchEvent
    data object MusicOpened : WatchEvent
    /** 1 play, 2 pause, 3 previous, 4 next, 5 volume up, 6 volume down. */
    data class MusicButton(val button: Int) : WatchEvent
    data class MusicVolume(val volume: Int) : WatchEvent
    data class CallAction(val accept: Boolean) : WatchEvent
    data object WeatherRequested : WatchEvent
    /** The user replied to a notification on the watch. */
    data class NotificationReply(val key: String, val text: String) : WatchEvent
    /** Camera remote: 1 open camera, 2 take picture, 3 close camera. */
    data class Camera(val action: Int) : WatchEvent
}

/**
 * Owns the Bluetooth connection to the watch: opens the RFCOMM socket, runs the
 * handshake, keeps the session alive with automatic reconnects, and exposes the
 * watch's features as suspend functions.
 *
 * The GT 4 is a "BR" device in Gadgetbridge terms: it talks over classic Bluetooth
 * RFCOMM on Huawei's SDP service UUID, not over BLE GATT.
 */
class WatchConnection(
    private val context: Context,
    private val store: AppStore,
    val log: ProtocolLog,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    private val _events = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WatchEvent> = _events

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    @Volatile private var link: WatchLink? = null
    private val opLock = Mutex()
    private var notificationId = 1
    private var contentLength = 200

    val uploader = FileUploader(log, scope)
    val history by lazy { com.nmnd.gtlink.data.HistoryDb(context) }

    fun connect() {
        if (job?.isActive == true) return
        val watch = store.watch.value ?: return
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                try {
                    runSession(watch.address)
                    attempt = 0
                } catch (e: Exception) {
                    log.add("! session ended: ${e.message}")
                    _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    closeSocket()
                }
                if (!store.settings.value.autoReconnect) break
                val wait = listOf(5L, 10L, 30L, 60L)[minOf(attempt, 3)]
                attempt++
                _state.update { it.copy(link = LinkState.WAITING_TO_RETRY, status = "Reconnecting in ${wait}s") }
                delay(wait * 1000)
            }
            _state.update { it.copy(link = LinkState.IDLE, status = "Disconnected") }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        closeSocket()
        _state.update { it.copy(link = LinkState.IDLE, status = "Disconnected") }
    }

    /** Forget this phone's pairing with the watch; the next connect pairs from scratch. */
    fun forgetPairing() {
        val watch = store.watch.value ?: return
        disconnect()
        store.forgetPairing(watch.address)
    }

    private fun closeSocket() {
        link?.cancelAll()
        link = null
        runCatching { socket?.close() }
        socket = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(address: String) {
        _state.update { it.copy(link = LinkState.CONNECTING, status = "Connecting", error = null) }
        val adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw WatchException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw WatchException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        val s = device.createRfcommSocketToServiceRecord(SDP_UUID)
        socket = s
        withContext(Dispatchers.IO) { s.connect() }
        log.add("socket connected to $address")

        val params = LinkParams()
        val out = s.outputStream
        val l = WatchLink(params, log) { bytes ->
            withContext(Dispatchers.IO) {
                out.write(bytes)
                out.flush()
            }
        }
        link = l

        val reader = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            val input = s.inputStream
            try {
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) l.onBytes(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                log.add("! socket read ended: ${e.message}")
            }
            l.cancelAll()
        }
        val eventPump = scope.launch { l.incoming.collect { handleIncoming(it) } }

        try {
            _state.update { it.copy(link = LinkState.AUTHENTICATING, status = "Authenticating") }
            Authenticator(l, log, store.pairing(address), address, Build.MODEL ?: "Android") { msg ->
                _state.update { it.copy(status = msg) }
            }.run()
            log.add("authenticated")
            initialise(l)
            _state.update { it.copy(link = LinkState.CONNECTED, status = "Connected", error = null) }
            reader.join()
        } finally {
            reader.cancel()
            eventPump.cancel()
        }
        throw WatchException("Connection lost")
    }

    private suspend fun initialise(l: WatchLink) {
        _state.update { it.copy(status = "Reading watch details") }
        l.request(Commands.productInfo())?.let { r ->
            _state.update {
                it.copy(
                    info = WatchInfo(
                        name = r.str(0x11) ?: store.watch.value?.name,
                        model = r.str(0x0A)?.trim(),
                        firmware = r.str(0x07),
                        hardware = r.str(0x03),
                        serial = r.str(0x09),
                    ),
                )
            }
        }
        l.request(Commands.setTime())
        l.request(Commands.battery())?.let { r -> _state.update { it.copy(battery = r.asInt(0x01, -1).takeIf { b -> b >= 0 }) } }

        val services = l.request(Commands.supportedServices())?.let { Commands.parseSupportedServices(it) } ?: emptySet()
        val commands = mutableMapOf<Int, Set<Int>>()
        for (p in Commands.supportedCommands(services.sorted(), l.params.sliceSize)) {
            l.request(p)?.let { commands += Commands.parseSupportedCommands(it) }
        }
        _state.update { it.copy(services = services, commands = commands) }

        if (_state.value.supports(0x01, 0x37)) {
            l.request(Commands.expandCapabilities(), 3_000)?.bytesOrNull(0x01)?.let { caps ->
                _state.update { it.copy(capabilities = caps) }
            }
        }
        // The account step is what lets a watch that was set up with Huawei Health accept
        // this phone without a reset (it must be the same Huawei account).
        if (_state.value.supports(0x1A, 0x05) && _state.value.supports(0x1A, 0x06)) {
            val account = store.settings.value.huaweiAccountId.trim()
            l.request(Commands.extendedAccount(account, _state.value.hasCapability(Commands.Capability.DIFF_ACCOUNT_PAIRING)), 3_000)
        }

        l.request(Commands.setTimeZoneId(), 3_000)
        for (p in SettingsSync.all(store.settings.value)) l.request(p, 3_000)

        l.request(Commands.notificationConstraints(), 3_000)?.let { parseContentLength(it)?.let { n -> contentLength = n } }
        pushAlarms(l)
        refreshActivity(l)
        refreshWatchfaces(l)
        l.request(Commands.watchfaceParams(), 3_000)?.let { r ->
            _state.update { it.copy(screenWidth = r.asInt(0x02, 0).takeIf { w -> w > 0 }, screenHeight = r.asInt(0x03, 0).takeIf { h -> h > 0 }) }
        }
        l.request(Commands.contactsCount(), 3_000)?.let { r ->
            _state.update { it.copy(contactSlots = r.asInt(0x01, 0).takeIf { n -> n > 0 }) }
        }
        if (_state.value.canCameraRemote && store.settings.value.cameraRemote) l.request(Commands.cameraSetup(0), 3_000)
    }

    private fun parseContentLength(r: Tlv): Int? = runCatching {
        r.obj(0x81).obj(0x82).obj(0x90).objects(0x91)
            .firstOrNull { it.byte(0x12).toInt() == 0x01 }
            ?.asInt(0x14, 0)?.takeIf { it > 16 }
    }.getOrNull()

    // --- watch-initiated messages ---------------------------------------------------

    private suspend fun handleIncoming(m: Incoming) {
        val l = link ?: return
        if (uploader.onMessage(l, m)) return
        when (m.service to m.command) {
            0x01 to 0x29 -> {
                l.send(Commands.cameraAck())
                val action = m.tlv.asInt(0x01, 0)
                if (action in 1..3) _events.emit(WatchEvent.Camera(action))
            }
            0x02 to 0x10 -> {
                val key = m.tlv.bytesOrNull(0x04)?.toString(Charsets.UTF_8)
                val encoding = m.tlv.asInt(0x02, 0)
                val text = m.tlv.bytesOrNull(0x06)?.let { String(it, if (encoding == 3) Charsets.UTF_16 else Charsets.UTF_8) }
                if (!key.isNullOrEmpty() && !text.isNullOrEmpty()) _events.emit(WatchEvent.NotificationReply(key, text))
            }
            0x25 to 0x09 -> {
                val index = m.tlv.asInt(0x01, 0)
                val song = uploader.lastMusic
                l.send(Commands.musicFileInfoReply(index, song?.title ?: "Unknown", song?.artist ?: "Unknown"))
            }
            0x01 to 0x08, 0x01 to 0x27 -> {
                val level = m.tlv.asInt(0x01, -1)
                if (level >= 0) _state.update { it.copy(battery = level) }
            }
            0x0B to 0x01 -> {
                val start = m.tlv.contains(0x01) && m.tlv.boolean(0x01)
                _events.emit(WatchEvent.FindPhone(start))
            }
            0x04 to 0x01 -> when (m.tlv.asInt(0x01, 0)) {
                1 -> _events.emit(WatchEvent.CallAction(accept = false))
                2 -> _events.emit(WatchEvent.CallAction(accept = true))
            }
            0x25 to 0x01 -> {
                val status = m.tlv.asInt(0x7F, -1)
                if (status == -1 || status == RESULT_OK) {
                    l.send(Commands.musicAck(0x01))
                    _events.emit(WatchEvent.MusicOpened)
                }
            }
            0x25 to 0x03 -> {
                val button = m.tlv.bytesOrNull(0x01)?.lastOrNull()?.toInt()
                val volume = m.tlv.bytesOrNull(0x02)?.lastOrNull()?.toInt()
                if (button != null && button in 1..6) _events.emit(WatchEvent.MusicButton(button))
                if (volume != null) _events.emit(WatchEvent.MusicVolume(volume))
                if (button != null || volume != null) l.send(Commands.musicAck(0x03))
            }
            0x0F to 0x04 -> {
                if (m.tlv.asInt(0x7F, -1) == 0x000186AA) {
                    _events.emit(WatchEvent.WeatherRequested)
                } else {
                    l.send(Commands.weatherDeviceReply(RESULT_OK))
                }
            }
        }
    }

    // --- features -------------------------------------------------------------------

    private fun requireLink(): WatchLink = link?.takeIf { _state.value.connected } ?: throw WatchException("Watch not connected")

    /** Runs [block] against the live link; returns false if not connected or it failed. */
    private suspend fun <T> withLink(block: suspend (WatchLink) -> T): Result<T> = runCatching {
        opLock.withLock { block(requireLink()) }
    }.onFailure { log.add("! ${it.message}") }

    suspend fun syncTime() = withLink {
        it.request(Commands.setTime())
        it.request(Commands.setTimeZoneId())
    }

    suspend fun refreshBattery() = withLink { l ->
        l.request(Commands.battery())?.let { r -> _state.update { it.copy(battery = r.asInt(0x01, -1).takeIf { b -> b >= 0 }) } }
    }

    suspend fun refreshActivity() = withLink { refreshActivity(it) }

    private suspend fun refreshActivity(l: WatchLink) {
        val r = l.request(Commands.fitnessTotals()) ?: return
        runCatching {
            var steps = 0
            var calories = 0
            var distance = 0
            for (t in r.obj(0x81).objects(0x83)) {
                if (t.contains(0x05)) steps += t.int(0x05)
                if (t.contains(0x06)) calories += t.short(0x06).toInt() and 0xFFFF
                if (t.contains(0x07)) distance += t.int(0x07)
            }
            _state.update { it.copy(activity = ActivityTotals(steps, calories, distance, System.currentTimeMillis())) }
        }
    }

    suspend fun applySettings(old: WatchSettings, new: WatchSettings) = withLink { l ->
        for (group in SettingsSync.changedGroups(old, new)) {
            for (p in SettingsSync.packets(new, group)) l.request(p, 3_000)
        }
    }

    suspend fun pushAllSettings() = withLink { l -> for (p in SettingsSync.all(store.settings.value)) l.request(p, 3_000) }

    suspend fun sendNotification(
        type: Int, title: String?, sender: String?, body: String?, sourceApp: String?,
        key: String? = null, phoneId: Int = 0, channelId: String? = null, replyable: Boolean = false,
    ) = withLink {
        val st = _state.value
        val extras = Commands.NotificationExtras(
            replyKey = if (replyable && st.canReplyToNotifications) key else null,
            notificationKey = if (st.hasCapability(Commands.Capability.NOTIFICATION_REMOVE_SINGLE) ||
                st.hasCapability(Commands.Capability.NOTIFICATION_REPEATED)) key else null,
            notificationId = phoneId,
            channelId = channelId,
            timestamp = st.hasCapability(Commands.Capability.NOTIFICATION_TIMESTAMP),
        )
        it.send(Commands.notification(nextNotificationId(), type, title, sender, body, sourceApp, maxBody = contentLength, extras = extras))
    }

    /** Clears a notification from the watch after it was dismissed on the phone. */
    suspend fun removeNotification(type: Int, sourceApp: String, key: String, phoneId: Int, channelId: String) = withLink {
        if (_state.value.hasCapability(Commands.Capability.NOTIFICATION_REMOVE_SINGLE)) {
            it.send(Commands.notificationRemove(type, sourceApp, key, phoneId, channelId))
        }
    }

    // --- camera remote ----------------------------------------------------------------

    /** Tell the watch the phone's camera is open (shows the shutter) or closed. */
    suspend fun setCameraOpen(open: Boolean) = withLink { it.send(Commands.cameraSetup(if (open) 1 else 2)) }

    suspend fun enableCameraRemote(enabled: Boolean) = withLink {
        if (enabled && _state.value.canCameraRemote) it.request(Commands.cameraSetup(0), 3_000)
    }

    // --- file upload --------------------------------------------------------------------

    /** Sends a watch face, watch app or song. Progress is on [uploader].state. */
    suspend fun upload(item: UploadItem) = withLink { uploader.start(it, item) }

    // --- history ------------------------------------------------------------------------

    /**
     * Downloads per-minute activity (steps, heart rate, SpO2) and sleep since the last
     * sync. A first sync looks back 14 days; the watch keeps roughly a week anyway.
     */
    suspend fun syncHistory(): Result<Int> = withLink { l ->
        _state.update { it.copy(historySync = it.historySync.copy(running = true, percent = 0)) }
        var added = 0
        try {
            val now = (System.currentTimeMillis() / 1000).toInt()
            val floor = now - 14 * 86_400
            val stepStart = maxOf(floor, (history.lastMinute() ?: 0) + 1)
            val stepCount = l.request(Commands.stepCount(stepStart, now))?.let { Commands.parseCount(it) } ?: 0
            val sleepStart = maxOf(floor, (history.lastSleep() ?: 0) + 1)
            val sleepCount = l.request(Commands.sleepCount(sleepStart, now))?.let { Commands.parseCount(it) } ?: 0
            val total = (stepCount + sleepCount).coerceAtLeast(1)
            log.add("history: $stepCount activity blocks, $sleepCount sleep blocks")
            for (i in 0 until stepCount) {
                val reply = l.request(Commands.stepData(i), 8_000) ?: break
                val samples = Commands.parseStepData(reply)
                history.insertMinutes(samples)
                added += samples.size
                _state.update { it.copy(historySync = it.historySync.copy(percent = (i + 1) * 100 / total)) }
            }
            for (i in 0 until sleepCount) {
                val reply = l.request(Commands.sleepData(i), 8_000) ?: break
                history.insertSleep(Commands.parseSleepData(reply))
                _state.update { it.copy(historySync = it.historySync.copy(percent = (stepCount + i + 1) * 100 / total)) }
            }
            _state.update {
                it.copy(historySync = HistorySyncState(false, 100, System.currentTimeMillis(), "$added minutes added"))
            }
        } catch (e: Exception) {
            _state.update { it.copy(historySync = it.historySync.copy(running = false, lastResult = e.message)) }
            throw e
        }
        added
    }

    private var callId = 0

    suspend fun sendIncomingCall(name: String, number: String?) = withLink {
        callId = nextNotificationId()
        it.send(Commands.call(callId, name, number))
    }

    suspend fun stopCall() = withLink { it.send(Commands.stopCall(callId)) }

    suspend fun sendMissedCall(name: String, number: String?) = withLink {
        it.send(Commands.notification(nextNotificationId(), Commands.NotificationType.MISSED_CALL, name, number, "Missed call", null))
    }

    suspend fun stopFindPhone() = withLink { it.send(Commands.stopFindPhone()) }

    suspend fun sendMusicInfo(artist: String, title: String, playState: Int, maxVolume: Int, volume: Int) = withLink {
        it.request(Commands.musicInfo(artist, title, playState, maxVolume, volume), 2_000)
    }

    suspend fun pushAlarms() = withLink { pushAlarms(it) }

    private suspend fun pushAlarms(l: WatchLink) {
        l.request(Commands.alarms(store.alarms.value), 3_000)
        val smart = store.smartAlarm.value
        l.request(Commands.smartAlarm(smart.enabled, smart.hour, smart.minute, smart.daysMask, smart.windowMinutes), 3_000)
    }

    /** Reads the alarms currently on the watch (they may have been edited there). */
    suspend fun readAlarms(): Result<List<WatchAlarm>> = withLink { l ->
        l.request(Commands.alarmList())?.let { Commands.parseAlarms(it) } ?: emptyList()
    }

    suspend fun refreshWatchfaces() = withLink { refreshWatchfaces(it) }

    private suspend fun refreshWatchfaces(l: WatchLink) {
        val faces = l.request(Commands.watchfaceList(), 4_000)?.let { Commands.parseWatchfaces(it) } ?: return
        val names = if (faces.isNotEmpty()) {
            l.request(Commands.watchfaceNames(faces), 4_000)?.let { Commands.parseWatchfaceNames(it) } ?: emptyMap()
        } else {
            emptyMap()
        }
        _state.update { s -> s.copy(watchFaces = faces.map { it.copy(displayName = names[it.fileName]) }) }
    }

    suspend fun setWatchface(face: WatchFace) = withLink { l ->
        l.request(Commands.watchfaceOperation(face, 1), 5_000)
        refreshWatchfaces(l)
    }

    suspend fun deleteWatchface(face: WatchFace) = withLink { l ->
        l.request(Commands.watchfaceOperation(face, 2), 5_000)
        refreshWatchfaces(l)
    }

    /** Replaces the watch's contact list. Returns how many were written. */
    suspend fun syncContacts(contacts: List<Pair<String, String>>) = withLink { l ->
        val slots = _state.value.contactSlots ?: l.request(Commands.contactsCount())?.asInt(0x01, 0) ?: 0
        if (slots <= 0) throw WatchException("The watch didn't report contact storage")
        val reply = l.request(Commands.contacts(contacts.take(slots), slots), 8_000)
        if (reply != null && !reply.resultOk()) throw WatchException("The watch rejected the contacts")
        minOf(slots, contacts.size)
    }

    suspend fun factoryReset() = withLink { it.send(Commands.factoryReset()) }

    suspend fun sendWeather(report: WeatherReport, fahrenheit: Boolean) = withLink { l ->
        val st = _state.value
        val start = l.request(Commands.weatherStart())
        if (start != null && !start.resultOk() && start.asInt(0x7F, 0) != 0x000186A3) {
            throw WatchException("The watch declined weather updates")
        }
        val caps = Commands.WeatherCaps()
        if (st.supports(0x0F, 0x05)) l.request(Commands.weatherUnit(fahrenheit), 2_000)
        l.request(Commands.weatherSupport())?.let { if (it.contains(0x01)) caps.bitmap = it.byte(0x01).toInt() and 0xFF }
        if (st.supports(0x0F, 0x06)) {
            l.request(Commands.weatherExtendedSupport(), 2_000)?.let { if (it.contains(0x01)) caps.extended = it.short(0x01).toInt() }
        }
        fun t(c: Double) = (if (fahrenheit) c * 9 / 5 + 32 else c).roundToInt()
        l.request(
            Commands.currentWeather(
                caps,
                icon = HuaweiWeatherIcons.fromWmo(report.wmoCode),
                windDirectionDeg = report.windDirectionDeg,
                beaufort = HuaweiWeatherIcons.beaufort(report.windSpeedKmh),
                minC = t(report.todayMinC), maxC = t(report.todayMaxC),
                location = report.locationName,
                currentC = t(report.currentTempC),
                fahrenheit = fahrenheit,
                observedSec = report.observedAtSec.toInt(),
            ),
        )
        if (st.supports(0x0F, 0x08)) {
            l.request(
                Commands.weatherForecast(
                    caps,
                    report.hourly.map { Commands.HourForecast(it.timeSec.toInt(), HuaweiWeatherIcons.fromWmo(it.wmoCode), t(it.tempC)) },
                    report.daily.map { Commands.DayForecast(it.dateSec.toInt(), HuaweiWeatherIcons.fromWmo(it.wmoCode), t(it.maxC), t(it.minC)) },
                ),
                5_000,
            )
        }
        _state.update { it.copy(lastWeatherSync = System.currentTimeMillis()) }
    }

    suspend fun reportWeatherUnavailable() = withLink { it.send(Commands.weatherError(2)) }

    /** For the protocol console: send a raw service/command/TLV-hex message. */
    suspend fun sendRaw(service: Int, command: Int, tlvHex: String, encrypt: Boolean) = withLink { l ->
        val tlv = if (tlvHex.isBlank()) Tlv() else Tlv().parse(tlvHex.replace(" ", "").hexToBytes())
        l.request(Packet(service, command, tlv, encrypt = encrypt), 4_000)
    }

    private fun nextNotificationId(): Int {
        notificationId = (notificationId % 0x7FFF) + 1
        return notificationId
    }

    companion object {
        val SDP_UUID: UUID = UUID.fromString("82FF3820-8411-400C-B85A-55BDB32CF060")
    }
}

private fun Tlv.str(tag: Int): String? = if (contains(tag)) string(tag) else null
