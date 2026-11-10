package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Packet
import com.nmnd.gtlink.protocol.Tlv
import java.util.Calendar

/**
 * Builders for every command the app sends. Each is a pure function from app-level
 * values to a [Packet], with the service/command ids and tag layout taken from
 * Gadgetbridge's packet definitions (devices/huawei/packets/). Keeping them pure is
 * what lets the unit tests pin the exact bytes.
 */
object Commands {
    // Service ids
    const val DEVICE_CONFIG = 0x01
    const val NOTIFICATIONS = 0x02
    const val CONTACTS = 0x03
    const val CALLS = 0x04
    const val FITNESS = 0x07
    const val ALARMS = 0x08
    const val FIND_PHONE = 0x0B
    const val LOCALE = 0x0C
    const val WEATHER = 0x0F
    const val WORK_MODE = 0x26
    const val MUSIC = 0x25
    const val WATCHFACE = 0x27

    /** Services the phone side claims to understand; the watch answers with a 0/1 mask. */
    val knownServices = byteArrayOf(
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
        0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14,
        0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1D, 0x20,
        0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2A, 0x2B, 0x2D, 0x2E,
        0x30, 0x32, 0x33, 0x34, 0x35,
    )

    // --- device config ------------------------------------------------------------

    fun productInfo() = Packet(
        DEVICE_CONFIG, 0x07,
        Tlv().apply { for (t in intArrayOf(0x01, 0x02, 0x07, 0x09, 0x0A, 0x11, 0x12, 0x16, 0x1A, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23)) put(t) },
    )

    fun battery() = Packet(DEVICE_CONFIG, 0x08, Tlv().put(0x01))

    fun supportedServices() = Packet(DEVICE_CONFIG, 0x02, Tlv().put(0x01, knownServices))

    /** Seconds since epoch + the zone offset as Huawei encodes it (hours with a sign bit, minutes). */
    fun timeAndZone(now: Calendar = Calendar.getInstance()): Triple<Int, Short, ByteArray> {
        val offset = (now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / 1000
        val hours = if (offset < 0) (-offset / 3600 + 128) else offset / 3600
        // Same arithmetic as Gadgetbridge's HuaweiUtil, sign quirks included.
        val minutes = offset / 60 % 60
        val zone = ((hours and 0xFF) shl 8 or (minutes and 0xFF)).toShort()
        return Triple((now.timeInMillis / 1000).toInt(), zone, now.timeZone.id.toByteArray())
    }

    fun setTime(now: Calendar = Calendar.getInstance()): Packet {
        val (secs, zone, _) = timeAndZone(now)
        return Packet(DEVICE_CONFIG, 0x05, Tlv().put(0x01, secs).put(0x02, zone))
    }

    fun setTimeZoneId(now: Calendar = Calendar.getInstance()): Packet {
        val (secs, zone, id) = timeAndZone(now)
        return Packet(DEVICE_CONFIG, 0x32, Tlv().put(0x01, secs).put(0x02, zone).put(0x03, id))
    }

    /** date: 1 = y/m/d, 2 = m/d/y, 3 = d/m/y. time: 1 = 12h, 2 = 24h. */
    fun dateFormat(date: Int, time: Int) =
        Packet(DEVICE_CONFIG, 0x04, Tlv().put(0x81, Tlv().put(0x02, date.toByte()).put(0x03, time.toByte())))

    fun activateOnLift(enabled: Boolean) = Packet(DEVICE_CONFIG, 0x09, Tlv().put(0x01, enabled))
    fun navigateOnRotate(enabled: Boolean) = Packet(DEVICE_CONFIG, 0x1B, Tlv().put(0x01, enabled))
    /** The watch takes 1 for the left wrist, 0 for the right. */
    fun wearLocation(left: Boolean) = Packet(DEVICE_CONFIG, 0x1A, Tlv().put(0x01, (if (left) 1 else 0).toByte()))

    fun dndDelete() = Packet(DEVICE_CONFIG, 0x0B, Tlv().put(0x81, Tlv().put(0x02, 0x01.toByte())))

    fun dndAdd(enabled: Boolean, startMinutes: Int, endMinutes: Int, daysMask: Int) = Packet(
        DEVICE_CONFIG, 0x0C,
        Tlv().put(
            0x81,
            Tlv().put(0x02, 0x01.toByte())
                .put(0x03, enabled)
                .put(0x04, 0x00.toByte())
                .put(0x05, hhmm(startMinutes))
                .put(0x06, hhmm(endMinutes))
                .put(0x07, daysMask.toByte()),
        ),
    )

    fun factoryReset() = Packet(DEVICE_CONFIG, 0x0D, Tlv().put(0x01, 0x01.toByte()))

    fun disconnectAlert(enabled: Boolean) = Packet(0x0B, 0x03, Tlv().put(0x01, enabled))

    // --- locale -----------------------------------------------------------------

    /** locale like "en-GB"; metric = true for km, false for miles. */
    fun language(locale: String, metric: Boolean) =
        Packet(LOCALE, 0x01, Tlv().put(0x01, locale.toByteArray()).put(0x02, (if (metric) 0 else 1).toByte()))

    fun temperatureUnit(fahrenheit: Boolean) = Packet(LOCALE, 0x05, Tlv().put(0x01, (if (fahrenheit) 1 else 0).toByte()))

    // --- notifications ------------------------------------------------------------

    object NotificationType {
        const val CALL = 0x01
        const val SMS = 0x02
        const val STOP = 0x0C
        const val MISSED_CALL = 0x0E
        const val EMAIL = 0x0F
        const val GENERIC = 0x7F
    }

    private const val TEXT = 0x01
    private const val SENDER = 0x02
    private const val TITLE = 0x03

    fun notification(
        id: Int,
        type: Int,
        title: String?,
        sender: String?,
        body: String?,
        sourceAppId: String?,
        contentFormat: Int = 0x02,
        maxBody: Int = 200,
        extras: NotificationExtras? = null,
    ): Packet {
        val elements = Tlv()
        fun element(textType: Int, value: String?) {
            val e = Tlv().put(0x0E, textType.toByte()).put(0x0F, contentFormat.toByte())
            if (!value.isNullOrEmpty()) e.put(0x10, value)
            elements.put(0x8D, e)
        }
        val trimmed = if (body != null && body.length > maxBody) body.take(maxBody - 3) + "..." else body
        element(TITLE, title)
        element(SENDER, sender)
        element(TEXT, trimmed)
        val t = Tlv()
            .put(0x01, (id and 0xFFFF).toShort())
            .put(0x02, type.toByte())
            .put(0x03, true)
            .put(0x84, Tlv().put(0x8C, elements))
        if (sourceAppId != null) t.put(0x11, sourceAppId.take(127))
        if (extras != null) {
            if (extras.replyKey != null) t.put(0x18, extras.replyKey)
            if (extras.timestamp) t.put(0x15, (System.currentTimeMillis() / 1000).toInt())
            if (extras.notificationKey != null) {
                t.put(0x19, extras.notificationKey)
                t.put(0x1A, extras.notificationId)
                t.put(0x1B, extras.channelId ?: "")
            }
        }
        return Packet(NOTIFICATIONS, 0x01, t)
    }

    /** Optional notification fields, sent only when the watch advertises the matching capability. */
    data class NotificationExtras(
        val replyKey: String? = null,
        val notificationKey: String? = null,
        val notificationId: Int = 0,
        val channelId: String? = null,
        val timestamp: Boolean = false,
    )

    fun call(id: Int, callerName: String, number: String?, incomingNumberSupported: Boolean = true): Packet {
        val elements = Tlv()
        elements.put(0x8D, Tlv().put(0x0E, TEXT.toByte()).put(0x0F, 0x02.toByte()).put(0x10, callerName))
        if (incomingNumberSupported && !number.isNullOrEmpty()) {
            elements.put(0x8D, Tlv().put(0x0E, 0x07.toByte()).put(0x0F, 0x02.toByte()).put(0x10, number))
        }
        return Packet(
            NOTIFICATIONS, 0x01,
            Tlv().put(0x01, (id and 0xFFFF).toShort()).put(0x02, NotificationType.CALL.toByte()).put(0x03, true)
                .put(0x84, Tlv().put(0x8C, elements)),
        )
    }

    /** Dismisses a ringing call screen on the watch. */
    fun stopCall(id: Int) = Packet(
        NOTIFICATIONS, 0x01,
        Tlv().put(0x01, (id and 0xFFFF).toShort()).put(0x02, NotificationType.STOP.toByte()).put(0x03, true).put(0x04),
    )

    fun notificationsEnabled(enabled: Boolean) =
        Packet(NOTIFICATIONS, 0x04, Tlv().put(0x81, Tlv().put(0x02, enabled).put(0x03, enabled)))

    /** "Don't push when not worn". The watch's flag is inverted. */
    fun pushWhenNotWorn(suppress: Boolean) = Packet(NOTIFICATIONS, 0x08, Tlv().put(0x01, !suppress))

    fun notificationConstraints() = Packet(NOTIFICATIONS, 0x02, Tlv().put(0x01))

    // --- fitness / health ---------------------------------------------------------

    fun fitnessTotals() = Packet(FITNESS, 0x03, Tlv().put(0x01))

    fun stepGoal(steps: Int) = Packet(
        FITNESS, 0x01,
        Tlv().put(0x81, Tlv().put(0x82, Tlv().put(0x03, 0x01.toByte()).put(0x04, 0x00.toByte()).put(0x05, steps))),
    )

    /** gender: 1 male, 2 female, 3 unknown. birthday as y/m/d. */
    fun userInfo(heightCm: Int, weightKg: Int, age: Int, year: Int, month: Int, day: Int, gender: Int): Packet {
        val t = Tlv()
            .put(0x01, heightCm.toByte())
            .put(0x02, weightKg.toByte())
            .put(0x03, age.toByte())
            .put(0x04, (year shl 16) + (month shl 8) + day)
        if (gender == 1 || gender == 2) t.put(0x05, gender.toByte())
        t.put(0x06, Math.round(0.42f * heightCm).toByte())
        t.put(0x07, Math.round(0.83f * heightCm).toByte())
        return Packet(FITNESS, 0x02, t)
    }

    fun inactivityReminder(enabled: Boolean, intervalMinutes: Int, startMinutes: Int, endMinutes: Int, daysMask: Int) = Packet(
        FITNESS, 0x07,
        Tlv().put(
            0x81,
            Tlv().put(0x02, enabled)
                .put(0x03, intervalMinutes.toByte())
                .put(0x04, hhmm(startMinutes))
                .put(0x05, hhmm(endMinutes))
                .put(0x06, daysMask.toByte()),
        ),
    )

    fun truSleep(enabled: Boolean) = Packet(FITNESS, 0x16, Tlv().put(0x01, enabled))
    fun continuousHeartRate(enabled: Boolean) = Packet(FITNESS, 0x17, Tlv().put(0x01, enabled))
    fun realtimeHeartRate(enabled: Boolean) = Packet(FITNESS, 0x1C, Tlv().put(0x01, enabled))
    fun continuousSpo2(enabled: Boolean) = Packet(FITNESS, 0x24, Tlv().put(0x01, enabled))
    fun skinTemperature(enabled: Boolean) = Packet(FITNESS, 0x2A, Tlv().put(0x01, 0x01.toByte()).put(0x02, enabled))

    /** bpm 0 disables the alert. */
    fun highHeartRateAlert(bpm: Int) = thresholdAlert(0x1D, bpm)
    fun lowHeartRateAlert(bpm: Int) = thresholdAlert(0x22, bpm)
    fun lowSpo2Alert(percent: Int) = thresholdAlert(0x25, percent)

    private fun thresholdAlert(command: Int, value: Int): Packet {
        val t = Tlv().put(0x01, value > 0)
        if (value > 0) t.put(0x02, value.toByte())
        return Packet(FITNESS, command, t)
    }

    fun automaticStress(enabled: Boolean) = Packet(0x20, 0x09, Tlv().put(0x01, (if (enabled) 1 else 0).toByte()))

    // --- alarms -------------------------------------------------------------------

    fun alarms(alarms: List<WatchAlarm>): Packet {
        val list = Tlv()
        alarms.forEachIndexed { i, a ->
            list.put(
                0x82,
                Tlv().put(0x03, (i + 1).toByte())
                    .put(0x04, a.enabled)
                    .put(0x05, ((a.hour shl 8) or (a.minute and 0xFF)).toShort())
                    .put(0x06, a.daysMask.toByte())
                    .put(0x07, a.name),
            )
        }
        if (alarms.isEmpty()) list.put(0x82, Tlv().put(0x03, 0x01.toByte()))
        return Packet(ALARMS, 0x01, Tlv().put(0x81, list))
    }

    fun alarmList() = Packet(ALARMS, 0x03, Tlv().put(0x01))

    fun smartAlarm(enabled: Boolean, hour: Int, minute: Int, daysMask: Int, windowMinutes: Int) = Packet(
        ALARMS, 0x02,
        Tlv().put(
            0x81,
            Tlv().put(
                0x82,
                Tlv().put(0x03, 0x01.toByte()).put(0x04, enabled)
                    .put(0x05, ((hour shl 8) or (minute and 0xFF)).toShort())
                    .put(0x06, daysMask.toByte()).put(0x07, windowMinutes.toByte()),
            ),
        ),
    )

    fun smartAlarmList() = Packet(ALARMS, 0x04, Tlv().put(0x01))

    fun parseAlarms(reply: Tlv): List<WatchAlarm> = runCatching {
        reply.obj(0x81).objects(0x82).mapNotNull { a ->
            if (!a.contains(0x05)) return@mapNotNull null
            val time = a.short(0x05).toInt()
            WatchAlarm(
                enabled = a.contains(0x04) && a.boolean(0x04),
                hour = (time shr 8) and 0xFF,
                minute = time and 0xFF,
                daysMask = if (a.contains(0x06)) a.byte(0x06).toInt() and 0x7F else 0,
                name = if (a.contains(0x07)) a.string(0x07) else "Alarm",
            )
        }
    }.getOrDefault(emptyList())

    // --- find phone, calls, music -------------------------------------------------

    fun stopFindPhone() = Packet(FIND_PHONE, 0x02, Tlv().put(0x01, 0x02.toByte()))

    /** Acknowledges a music command from the watch (command 1 = opened, 3 = button). */
    fun musicAck(command: Int) = Packet(MUSIC, command, Tlv().put(0x7F, RESULT_OK))

    /** playState uses android.media.session.PlaybackState values (3 = playing, 2 = paused). */
    fun musicInfo(artist: String, title: String, playState: Int, maxVolume: Int, volume: Int) = Packet(
        MUSIC, 0x02,
        Tlv().put(0x01, artist).put(0x02, title).put(0x03, playState.toByte())
            .put(0x04, maxVolume.toByte()).put(0x05, volume.toByte()),
    )

    // --- contacts -----------------------------------------------------------------

    fun contactsCount() = Packet(CONTACTS, 0x02, Tlv().put(0x01).put(0x02))

    fun contacts(contacts: List<Pair<String, String>>, maxCount: Int): Packet {
        val list = Tlv()
        for (i in 0 until maxCount) {
            val c = Tlv().put(0x03, (i + 1).toByte())
            if (i < contacts.size) {
                c.put(0x04, contacts[i].first)
                    .put(0x85, Tlv().put(0x86, Tlv().put(0x07, "Mobile").put(0x08, contacts[i].second)))
            }
            list.put(0x82, c)
        }
        return Packet(CONTACTS, 0x01, Tlv().put(0x81, list))
    }

    // --- watch faces --------------------------------------------------------------

    fun watchfaceList() = Packet(WATCHFACE, 0x02, Tlv().put(0x01).put(0x06, 0x03.toByte()))

    fun watchfaceNames(faces: List<WatchFace>): Packet {
        val list = Tlv()
        faces.forEach { list.put(0x83, Tlv().put(0x04, it.fileName)) }
        return Packet(WATCHFACE, 0x06, Tlv().put(0x01, 0x01.toByte()).put(0x82, list))
    }

    /** operation 1 = make active, 2 = delete. */
    fun watchfaceOperation(face: WatchFace, operation: Int) = Packet(
        WATCHFACE, 0x03,
        Tlv().put(0x01, face.fileName).put(0x02, face.version).put(0x03, operation.toByte()),
    )

    fun parseWatchfaces(reply: Tlv): List<WatchFace> = runCatching {
        if (!reply.contains(0x81)) return@runCatching emptyList()
        reply.obj(0x81).objects(0x82).map {
            val type = it.byte(0x05).toInt()
            WatchFace(
                fileName = it.string(0x03),
                version = it.string(0x04),
                current = type and 1 == 1,
                factory = (type shr 1) and 1 == 1,
            )
        }
    }.getOrDefault(emptyList())

    fun parseWatchfaceNames(reply: Tlv): Map<String, String> = runCatching {
        if (!reply.contains(0x82)) return@runCatching emptyMap()
        reply.obj(0x82).objects(0x83).associate { it.string(0x04) to it.string(0x05) }
    }.getOrDefault(emptyMap())

    // --- weather ------------------------------------------------------------------

    fun weatherStart() = Packet(WEATHER, 0x09, Tlv().put(0x01, 0x03.toByte()))
    fun weatherSupport() = Packet(WEATHER, 0x02, Tlv().put(0x01))
    fun weatherExtendedSupport() = Packet(WEATHER, 0x06, Tlv().put(0x01))
    fun weatherUnit(fahrenheit: Boolean) = Packet(WEATHER, 0x05, Tlv().put(0x01, (if (fahrenheit) 1 else 0).toByte()))
    fun weatherDeviceReply(code: Int) = Packet(WEATHER, 0x04, Tlv().put(0x01, code), encrypt = false)
    fun weatherError(code: Int) = Packet(WEATHER, 0x07, Tlv().put(0x01, code.toByte()))

    class WeatherCaps(var bitmap: Int = 0xFF, var extended: Int = 0x07) {
        val wind get() = bitmap and 0x02 != 0
        val pm25 get() = bitmap and 0x04 != 0
        val temperature get() = bitmap and 0x08 != 0
        val location get() = bitmap and 0x10 != 0
        val currentTemperature get() = bitmap and 0x20 != 0
        val unit get() = bitmap and 0x40 != 0
        val time get() = extended and 0x01 != 0
        val source get() = extended and 0x02 != 0
        val icon get() = extended and 0x04 != 0
    }

    fun currentWeather(
        caps: WeatherCaps,
        icon: Byte, windDirectionDeg: Int, beaufort: Int, minC: Int, maxC: Int,
        location: String, currentC: Int, fahrenheit: Boolean, observedSec: Int,
    ): Packet {
        val t = Tlv()
        val t81 = Tlv()
        if (caps.icon) t81.put(0x02, icon)
        if (caps.wind) {
            val dir = if (windDirectionDeg in 0..360) ((windDirectionDeg / 45.0 + 0.5).toInt() % 8).let { if (it == 0) 8 else it } else 0
            t81.put(0x03, ((dir shl 8) or (beaufort and 0xFF)).toShort())
        }
        if (caps.icon || caps.wind) t.put(0x81, t81)
        if (caps.temperature) t.put(0x85, Tlv().put(0x06, minC.toByte()).put(0x07, maxC.toByte()))
        if (caps.location) t.put(0x08, location)
        if (caps.currentTemperature) t.put(0x09, currentC.toByte())
        if (caps.unit) t.put(0x0A, (if (fahrenheit) 1 else 0).toByte())
        if (caps.time) t.put(0x0C, observedSec)
        if (caps.source) t.put(0x0E, "Open-Meteo")
        return Packet(WEATHER, 0x01, t)
    }

    class HourForecast(val timeSec: Int, val icon: Byte, val tempC: Int)
    class DayForecast(val timeSec: Int, val icon: Byte, val maxC: Int, val minC: Int)

    fun weatherForecast(caps: WeatherCaps, hours: List<HourForecast>, days: List<DayForecast>): Packet {
        val t = Tlv()
        if (hours.isNotEmpty()) {
            val h = Tlv()
            hours.forEach {
                val e = Tlv().put(0x03, it.timeSec)
                if (caps.icon) e.put(0x04, it.icon)
                if (caps.temperature || caps.currentTemperature) e.put(0x05, it.tempC.toByte())
                h.put(0x82, e)
            }
            t.put(0x81, h)
        }
        if (days.isNotEmpty()) {
            val d = Tlv()
            days.forEach {
                val e = Tlv().put(0x12, it.timeSec)
                if (caps.icon) e.put(0x13, it.icon)
                if (caps.temperature) e.put(0x14, it.maxC.toByte()).put(0x15, it.minC.toByte())
                d.put(0x91, e)
            }
            t.put(0x90, d)
        }
        return Packet(WEATHER, 0x08, t)
    }

    // --- capabilities & account -----------------------------------------------------

    /** A bitmap of optional features; see [hasExpandCapability]. */
    fun expandCapabilities() = Packet(DEVICE_CONFIG, 0x37, Tlv().put(0x01))

    fun hasExpandCapability(caps: ByteArray?, which: Int): Boolean {
        if (caps == null || which >= caps.size * 8) return false
        val bit = 1 shl (which % 8)
        return caps[which / 8].toInt() and bit == bit
    }

    object Capability {
        const val SEND_COUNTRY_CODE = 30
        const val NOTIFICATION_REPLY_ACTIONS = 73
        const val NOTIFICATION_TIMESTAMP = 77
        const val NOTIFICATION_REPLY = 89
        const val NOTIFICATION_REPEATED = 94
        const val NOTIFICATION_REMOVE_SINGLE = 120
        const val DIFF_ACCOUNT_PAIRING = 0xAC
    }

    /**
     * Tells the watch which Huawei account this phone belongs to. When it matches the
     * account the watch was set up with, the watch accepts this phone without a factory
     * reset (Gadgetbridge PR #3721).
     */
    fun extendedAccount(accountId: String, diffAccountPairing: Boolean): Packet {
        val t = Tlv()
        if (accountId.isNotEmpty()) t.put(0x01, accountId) else t.put(0x01, 0x00.toByte())
        if (diffAccountPairing) t.put(0x03, 0x01.toByte())
        return Packet(0x1A, 0x05, t)
    }

    // --- camera remote --------------------------------------------------------------

    /** 0 = remote available, 1 = camera open and ready, 2 = camera closed. */
    fun cameraSetup(state: Int) = Packet(DEVICE_CONFIG, 0x2A, Tlv().put(0x01, state.toByte()))
    fun cameraAck() = Packet(DEVICE_CONFIG, 0x29, Tlv().put(0x7F, RESULT_OK))

    // --- notification extras --------------------------------------------------------

    fun notificationRemove(type: Int, sourceAppId: String, key: String, id: Int, channelId: String) = Packet(
        NOTIFICATIONS, 0x06,
        Tlv().put(0x01, type.toByte()).put(0x02, sourceAppId).put(0x03, key).put(0x04, id).put(0x05, channelId),
    )

    // --- activity history -----------------------------------------------------------

    fun stepCount(startSec: Int, endSec: Int) = Packet(FITNESS, 0x0A, Tlv().put(0x81).put(0x03, startSec).put(0x04, endSec))
    fun stepData(index: Int) = Packet(FITNESS, 0x0B, Tlv().put(0x81, Tlv().put(0x02, index.toShort())))
    fun sleepCount(startSec: Int, endSec: Int) = Packet(FITNESS, 0x0C, Tlv().put(0x81).put(0x03, startSec).put(0x04, endSec))
    fun sleepData(index: Int) = Packet(FITNESS, 0x0D, Tlv().put(0x81, Tlv().put(0x02, index.toShort())))

    fun parseCount(reply: Tlv): Int = runCatching { reply.obj(0x81).short(0x02).toInt() and 0xFFFF }.getOrDefault(0)

    /** One minute of activity. Fields the watch didn't record are null. */
    data class MinuteSample(
        val timeSec: Int,
        val steps: Int?,
        val calories: Int?,
        val distance: Int?,
        val heartRate: Int?,
        val spo2: Int?,
        val restingHeartRate: Int?,
    )

    fun parseStepData(reply: Tlv): List<MinuteSample> = runCatching {
        val c = reply.obj(0x81)
        val base = c.int(0x03)
        c.objects(0x84).mapNotNull { sub ->
            val offset = sub.byte(0x05).toInt() and 0xFF
            parseMinute(base + 60 * offset, sub.bytes(0x06))
        }
    }.getOrDefault(emptyList())

    /** Port of Gadgetbridge's StepResponse.parseData: two feature bitmaps, then values in bit order. */
    internal fun parseMinute(time: Int, data: ByteArray): MinuteSample? {
        if (data.isEmpty()) return null
        var i = 0
        val bitmap1 = data[i++].toInt() and 0xFF
        var bitmap2 = 0
        if (bitmap1 and 0x80 != 0) {
            if (data.size <= i) return null
            bitmap2 = data[i++].toInt() and 0xFF
        }
        var steps: Int? = null
        var calories: Int? = null
        var distance: Int? = null
        var hr: Int? = null
        var spo2: Int? = null
        var resting: Int? = null
        var bit = 1
        while (bit < 0x80) {
            if (bitmap1 and bit != 0) {
                val value: Int
                if (bit == 0x20 || bit == 0x40) {
                    if (data.size - 1 < i) return null
                    value = data[i++].toInt() and 0xFF
                } else {
                    if (data.size - 2 < i) return null
                    value = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                    i += 2
                }
                when (bit) {
                    0x02 -> steps = value
                    0x04 -> calories = value
                    0x08 -> distance = value
                    0x40 -> hr = value
                }
            }
            bit = bit shl 1
        }
        bit = 1
        while (bit < 0x100) {
            if (bitmap2 and bit != 0) {
                if (data.size - 1 < i) return null
                val value = data[i++].toInt() and 0xFF
                when (bit) {
                    0x01 -> spo2 = value
                    0x02 -> resting = value
                }
            }
            bit = bit shl 1
        }
        return MinuteSample(time, steps, calories, distance, hr?.takeIf { it in 1..254 }, spo2?.takeIf { it in 1..100 }, resting?.takeIf { it in 1..254 })
    }

    /** A stretch of sleep. type is Huawei's raw code: 0x06 light, 0x07 deep (Gadgetbridge RawTypes). */
    data class SleepSegment(val startSec: Int, val endSec: Int, val type: Int) {
        val deep get() = type == 0x07
    }

    fun parseSleepData(reply: Tlv): List<SleepSegment> = runCatching {
        reply.obj(0x81).objects(0x83).map { sub ->
            val type = sub.byte(0x04).toInt() and 0xFF
            val t = sub.bytes(0x05)
            val start = ((t[0].toInt() and 0xFF) shl 24) or ((t[1].toInt() and 0xFF) shl 16) or
                ((t[2].toInt() and 0xFF) shl 8) or (t[3].toInt() and 0xFF)
            val minutes = ((t[4].toInt() and 0xFF) shl 8) or (t[5].toInt() and 0xFF)
            SleepSegment(start, start + minutes * 60, type)
        }
    }.getOrDefault(emptyList())

    // --- file upload (watch faces, apps, music) -------------------------------------

    object FileType {
        const val WATCHFACE = 1
        const val MUSIC = 2
        const val APP = 6
    }

    fun fileInfo(fileName: String, size: Int, type: Int): Packet {
        val t = Tlv().put(0x01, fileName).put(0x02, size).put(0x03, type.toByte())
        if (type == FileType.WATCHFACE) {
            val parts = fileName.split("_")
            t.put(0x05, parts[0]).put(0x06, parts.getOrElse(1) { "1.0.0" })
        }
        return Packet(0x28, 0x02, t)
    }

    fun fileHash(fileId: Int, sha256: ByteArray) = Packet(0x28, 0x03, Tlv().put(0x01, fileId.toByte()).put(0x03, sha256))

    fun fileConsultAck(fileId: Int, noEncrypt: Boolean): Packet {
        val t = Tlv().put(0x7F, RESULT_OK).put(0x01, fileId.toByte())
        if (noEncrypt) t.put(0x09, 0x01.toByte())
        return Packet(0x28, 0x04, t)
    }

    fun fileComplete(fileId: Int) = Packet(0x28, 0x07, Tlv().put(0x7F, RESULT_OK).put(0x01, fileId.toByte()))

    /** The watch asks for a song's title/artist after a music upload. */
    fun musicFileInfoReply(songIndex: Int, title: String, artist: String) =
        Packet(MUSIC, 0x09, Tlv().put(0x01, songIndex.toShort()).put(0x03, title).put(0x04, artist))

    fun watchfaceParams() = Packet(WATCHFACE, 0x01, Tlv().put(0x01).put(0x02).put(0x03).put(0x04).put(0x05).put(0x0E).put(0x0F))

    // --- helpers ------------------------------------------------------------------

    /** Minutes since midnight to the watch's two-byte {hour, minute}. */
    fun hhmm(minutes: Int) = byteArrayOf((minutes / 60).toByte(), (minutes % 60).toByte())

    /** The commands the phone side handles, per service. The watch replies with a 0/1 mask per list. */
    val commandsPerService: Map<Int, ByteArray> = mapOf(
        0x01 to bytes(0x04, 0x07, 0x08, 0x09, 0x0A, 0x0D, 0x0E, 0x10, 0x11, 0x12, 0x13, 0x14, 0x1B, 0x1A, 0x1D, 0x21, 0x22, 0x23, 0x24, 0x29, 0x2A, 0x2B, 0x32, 0x2E, 0x31, 0x30, 0x35, 0x36, 0x37, 0x2F),
        0x02 to bytes(0x01, 0x04, 0x05, 0x06, 0x07, 0x08),
        0x03 to bytes(0x01, 0x03, 0x04),
        0x04 to bytes(0x01),
        0x05 to bytes(0x01),
        0x06 to bytes(0x01),
        0x07 to bytes(0x01, 0x03, 0x05, 0x07, 0x08, 0x09, 0x0A, 0x0E, 0x10, 0x13, 0x16, 0x15, 0x17, 0x18, 0x1B, 0x1C, 0x1D, 0x1E, 0x21, 0x22, 0x23, 0x24, 0x25, 0x28, 0x29, 0x06, 0x1F),
        0x08 to bytes(0x01, 0x02, 0x03),
        0x09 to bytes(0x01, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F),
        0x0A to bytes(0x01, 0x09, 0x0A),
        0x0B to bytes(0x01, 0x03),
        0x0C to bytes(0x01),
        0x0D to bytes(0x01),
        0x0F to bytes(0x01, 0x03, 0x05, 0x06, 0x07, 0x08, 0x0A, 0x0B, 0x0C),
        0x10 to bytes(0x01), 0x11 to bytes(0x01), 0x12 to bytes(0x01), 0x13 to bytes(0x01),
        0x14 to bytes(0x01), 0x15 to bytes(0x01),
        0x16 to bytes(0x01, 0x03, 0x07),
        0x17 to bytes(0x01, 0x04, 0x06, 0x07, 0x0B, 0x0C, 0x10, 0x12, 0x15, 0x17),
        0x18 to bytes(0x01, 0x02, 0x04, 0x05, 0x06, 0x09),
        0x19 to bytes(0x01, 0x04),
        0x1A to bytes(0x01, 0x03, 0x07, 0x05, 0x06),
        0x1B to bytes(0x01, 0x0F, 0x19, 0x1A),
        0x1D to bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A),
        0x20 to bytes(0x01, 0x02, 0x03, 0x04, 0x09, 0x0A),
        0x22 to bytes(0x01),
        0x23 to bytes(0x02, 0x0B),
        0x24 to bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C),
        0x25 to bytes(0x02, 0x04, 0x0E),
        0x26 to bytes(0x02, 0x03),
        0x27 to bytes(0x01, 0x0E),
        0x2A to bytes(0x01, 0x06),
        0x2B to bytes(0x12),
        0x2D to bytes(0x01),
        0x2E to bytes(0x01, 0x02, 0x03),
        0x30 to bytes(0x01), 0x32 to bytes(0x01),
        0x33 to bytes(0x01, 0x02),
        0x34 to bytes(0x01),
        0x35 to bytes(0x03, 0x04),
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Splits the services into requests that each fit one slice, as Gadgetbridge does. */
    fun supportedCommands(services: List<Int>, sliceSize: Int): List<Packet> {
        var maxSize = sliceSize - 32
        maxSize = maxSize - (maxSize % 16) - 4
        val out = mutableListOf<Packet>()
        var current = Tlv()
        for (service in services) {
            val commands = commandsPerService[service] ?: continue
            if (current.items.isNotEmpty() && current.serialize().size + commands.size + 5 > maxSize) {
                out += Packet(DEVICE_CONFIG, 0x03, Tlv().put(0x81, current))
                current = Tlv()
            }
            current.put(0x02, service.toByte()).put(0x03, commands)
        }
        if (current.items.isNotEmpty()) out += Packet(DEVICE_CONFIG, 0x03, Tlv().put(0x81, current))
        return out
    }

    /** service id -> the command ids the watch says it supports. */
    fun parseSupportedCommands(reply: Tlv): Map<Int, Set<Int>> {
        val out = mutableMapOf<Int, Set<Int>>()
        val container = runCatching { reply.obj(0x81) }.getOrNull() ?: return out
        var service = -1
        for (item in container.items) {
            when (item.tag) {
                0x02 -> service = item.value[0].toInt() and 0xFF
                0x04 -> {
                    val known = commandsPerService[service] ?: continue
                    out[service] = item.value.indices
                        .filter { item.value[it].toInt() == 1 && it < known.size }
                        .map { known[it].toInt() and 0xFF }
                        .toSet()
                }
            }
        }
        return out
    }

    fun parseSupportedServices(reply: Tlv): Set<Int> {
        val mask = reply.bytesOrNull(0x02) ?: return emptySet()
        val out = mutableSetOf(0x01)
        for (i in mask.indices) if (mask[i].toInt() == 1 && i < knownServices.size) out += knownServices[i].toInt()
        return out
    }
}

data class WatchAlarm(
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    /** Bit 0 = Monday ... bit 6 = Sunday; 0 = once. */
    val daysMask: Int = 0,
    val name: String = "Alarm",
)

data class WatchFace(
    val fileName: String,
    val version: String,
    val current: Boolean,
    val factory: Boolean,
    val displayName: String? = null,
)
