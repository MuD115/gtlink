package com.nmnd.gtlink.link

import com.nmnd.gtlink.data.SettingsSync
import com.nmnd.gtlink.data.WatchSettings
import com.nmnd.gtlink.protocol.Tlv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Expected layouts mirror Gadgetbridge's packet tests (TestAlarms, TestLocaleConfig, TestNotifications). */
class CommandsTest {
    @Test
    fun eventAlarms() {
        val p = Commands.alarms(listOf(WatchAlarm(true, 0x13, 0x37, 0, "Alarm1"), WatchAlarm(false, 0xCA, 0xFE, 1, "Alarm2")))
        val expected = Tlv().put(
            0x81,
            Tlv()
                .put(0x82, Tlv().put(0x03, 1.toByte()).put(0x04, true).put(0x05, 0x1337.toShort()).put(0x06, 0.toByte()).put(0x07, "Alarm1"))
                .put(0x82, Tlv().put(0x03, 2.toByte()).put(0x04, false).put(0x05, 0xCAFE.toShort()).put(0x06, 1.toByte()).put(0x07, "Alarm2")),
        )
        assertEquals(0x08, p.service)
        assertEquals(0x01, p.command)
        assertEquals(expected.toString(), p.tlv.toString())
        assertEquals(2, Commands.parseAlarms(p.tlv).size)
    }

    @Test
    fun locale() {
        val p = Commands.language("EN-GB", metric = true)
        assertEquals(Tlv().put(0x01, byteArrayOf(0x45, 0x4e, 0x2d, 0x47, 0x42)).put(0x02, 0.toByte()).toString(), p.tlv.toString())
    }

    @Test
    fun notificationLayout() {
        val p = Commands.notification(7, Commands.NotificationType.GENERIC, "Title", "Sender", "Body", "com.example")
        val t = p.tlv
        assertEquals(7, t.short(0x01).toInt())
        val elements = t.obj(0x84).obj(0x8C).objects(0x8D)
        assertEquals(listOf(3, 2, 1), elements.map { it.byte(0x0E).toInt() })
        assertEquals("Body", elements[2].string(0x10))
        assertEquals("com.example", t.string(0x11))
    }

    @Test
    fun timeZoneEncoding() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")) // +05:30
        val (_, zone, id) = Commands.timeAndZone(cal)
        assertEquals((5 shl 8) or 30, zone.toInt())
        assertEquals("Asia/Kolkata", String(id))
        val west = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")) // -03:00, no DST
        val (_, z2, _) = Commands.timeAndZone(west)
        assertEquals((3 + 128) shl 8, z2.toInt() and 0xFFFF)
    }

    @Test
    fun supportedCommandsRoundTrip() {
        val requests = Commands.supportedCommands(listOf(0x01, 0x02, 0x07, 0x25, 0x99), 0xF4)
        assertTrue(requests.isNotEmpty())
        // Watch says: of service 0x02's six commands, only the 1st and 3rd are supported.
        val reply = Tlv().put(0x81, Tlv().put(0x02, 0x02.toByte()).put(0x04, byteArrayOf(1, 0, 1, 0, 0, 0)))
        assertEquals(mapOf(0x02 to setOf(0x01, 0x05)), Commands.parseSupportedCommands(reply))
    }

    @Test
    fun settingsDiffOnlyResendsChangedGroup() {
        val a = WatchSettings()
        val b = a.copy(liftToWake = !a.liftToWake)
        assertEquals(listOf(SettingsSync.Group.DISPLAY), SettingsSync.changedGroups(a, b))
        assertTrue(SettingsSync.all(a).size > 15)
    }
}
