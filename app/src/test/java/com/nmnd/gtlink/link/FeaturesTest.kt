package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Frame
import com.nmnd.gtlink.protocol.LinkParams
import com.nmnd.gtlink.protocol.Tlv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FeaturesTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val zeroKey = LinkParams().apply { secretKey = ByteArray(16); ivOverride = ByteArray(16) }
    private val header = b(0x7c, 0x01, 0x01, 0x7d, 0x10) + ByteArray(16) + b(0x7e, 0x10)

    // Vectors from Gadgetbridge's TestFitnessData (zero key, zero IV, AES-CBC).

    @Test
    fun sleepCountRequestBytes() {
        val out = Frame.encode(Commands.sleepCount(0, 0x01020304), zeroKey)
        val expected = b(0x5a, 0x00, 0x2a, 0x00, 0x07, 0x0c) + header +
            b(0x32, 0x69, 0x7b, 0x51, 0x85, 0x20, 0x9b, 0x16, 0x6b, 0x93, 0x8a, 0x3d, 0xd5, 0x9a, 0xf9, 0x29, 0xdf, 0x07)
        assertArrayEquals(expected, out.single())
    }

    @Test
    fun stepCountRequestBytes() {
        val out = Frame.encode(Commands.stepCount(0x01020304, 0x10203040), zeroKey)
        val expected = b(0x5a, 0x00, 0x2a, 0x00, 0x07, 0x0a) + header +
            b(0x4d, 0x52, 0x79, 0x57, 0x49, 0x30, 0x75, 0xc6, 0x28, 0x5b, 0x79, 0xd5, 0xab, 0x89, 0x0d, 0x1e, 0xa9, 0xc9)
        assertArrayEquals(expected, out.single())
    }

    @Test
    fun dataRequestBytes() {
        val cipher = b(0xf6, 0xfb, 0xc0, 0xb6, 0x4f, 0x9a, 0xfa, 0x77, 0x53, 0x28, 0x7d, 0x13, 0xca, 0x49, 0xda, 0xfd)
        assertArrayEquals(b(0x5a, 0x00, 0x2a, 0x00, 0x07, 0x0d) + header + cipher + b(0x7d, 0xad), Frame.encode(Commands.sleepData(0x1337), zeroKey).single())
        assertArrayEquals(b(0x5a, 0x00, 0x2a, 0x00, 0x07, 0x0b) + header + cipher + b(0x3a, 0x37), Frame.encode(Commands.stepData(0x1337), zeroKey).single())
    }

    @Test
    fun countResponseParses() {
        val raw = b(0x5a, 0x00, 0x2a, 0x00, 0x07, 0x0c) + header +
            b(0xf6, 0xfb, 0xc0, 0xb6, 0x4f, 0x9a, 0xfa, 0x77, 0x53, 0x28, 0x7d, 0x13, 0xca, 0x49, 0xda, 0xfd, 0x93, 0x09)
        val m = Frame.Reader().feed(raw).single()
        assertEquals(0x1337, Commands.parseCount(Frame.decode(m, zeroKey)!!))
    }

    @Test
    fun minuteSampleParsing() {
        // steps 0x0102, calories 0x0003, distance 0x0004, heart rate 72
        val s = Commands.parseMinute(1000, b(0x4E, 0x01, 0x02, 0x00, 0x03, 0x00, 0x04, 72))!!
        assertEquals(258, s.steps)
        assertEquals(3, s.calories)
        assertEquals(4, s.distance)
        assertEquals(72, s.heartRate)
        // Second bitmap: SpO2 97, resting HR 55.
        val t = Commands.parseMinute(1000, b(0x82, 0x03, 0x00, 0x10, 97, 55))!!
        assertEquals(16, t.steps)
        assertEquals(97, t.spo2)
        assertEquals(55, t.restingHeartRate)
    }

    @Test
    fun sleepSegmentParsing() {
        val reply = Tlv().put(
            0x81,
            Tlv().put(0x02, 0.toShort()).put(0x83, Tlv().put(0x04, 0x07.toByte()).put(0x05, b(0x60, 0x00, 0x00, 0x00, 0x00, 0x5A))),
        )
        val seg = Commands.parseSleepData(reply).single()
        assertEquals(0x60000000, seg.startSec)
        assertEquals(0x60000000 + 90 * 60, seg.endSec)
        assertTrue(seg.deep)
    }

    @Test
    fun accountPacket() {
        assertEquals(Tlv().put(0x01, "123456789012345678").put(0x03, 0x01.toByte()).toString(), Commands.extendedAccount("123456789012345678", true).tlv.toString())
        assertEquals(Tlv().put(0x01, 0x00.toByte()).toString(), Commands.extendedAccount("", false).tlv.toString())
        assertTrue(Commands.hasExpandCapability(byteArrayOf(0, 0, 0, 0x02), 25))
    }

    // --- packages -----------------------------------------------------------------

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun watchfacePackage() {
        val bin = ByteArray(300) { it.toByte() }
        val xml = "﻿<HwTheme><title>Orbit</title><version>1.2</version><screen>466*466</screen></HwTheme>".toByteArray()
        val hwt = zip("description.xml" to xml, "com.huawei.watchface" to zip("watchface.bin" to bin), "preview/cover.jpg" to b(1, 2))
        val item = WatchPackages.prepare("orbit.hwt", hwt, null, null, null)
        assertEquals(Commands.FileType.WATCHFACE, item.type)
        assertArrayEquals(bin, item.data)
        assertEquals("Orbit", item.title)
        assertEquals("466*466", item.screen)
        assertTrue(item.fileName.matches(Regex("\\d{9}_1\\.0\\.0")))
    }

    @Test
    fun appPackage() {
        val name = "com.example.timer"
        val bytes = byteArrayOf(0xBE.toByte()) + java.nio.ByteBuffer.allocate(4).putInt(name.length).array() + name.toByteArray() + ByteArray(10)
        val item = WatchPackages.prepare("timer.bin", bytes, null, null, null)
        assertEquals(Commands.FileType.APP, item.type)
        assertEquals("com.example.timer_INSTALL", item.fileName)
    }

    // --- upload state machine -----------------------------------------------------------

    @Test
    fun uploadFollowsTheWatch() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        val link = WatchLink(LinkParams(), ProtocolLog()) { sent += it }
        val uploader = FileUploader(ProtocolLog(), CoroutineScope(Dispatchers.Unconfined))
        val data = ByteArray(250) { (it * 7).toByte() }
        uploader.start(link, UploadItem("123456789_1.0.0", Commands.FileType.WATCHFACE, data, "Face"))
        fun lastMessage() = Frame.Reader().feed(sent.last()).single()

        assertEquals(0x02, lastMessage().command)
        uploader.onMessage(link, Incoming(0x28, 0x03, Tlv().put(0x01, 0x05.toByte())))
        assertEquals(0x03, lastMessage().command)

        uploader.onMessage(link, Incoming(0x28, 0x04, Tlv().put(0x01, 0x05.toByte()).put(0x05, 100).put(0x08, 0).put(0x09, 0x01.toByte())))
        val ack = Frame.decode(lastMessage(), link.params)!!
        assertEquals(1, ack.asInt(0x09, 0))

        sent.clear()
        uploader.onMessage(link, Incoming(0x28, 0x05, Tlv().put(0x02, 0).put(0x03, 250)))
        val chunks = sent.flatMap { Frame.Reader().feed(it) }
        assertEquals(3, chunks.size)
        assertEquals(listOf(0x06, 0x06, 0x06), chunks.map { it.command })
        // fileId, index, offset, then data
        assertEquals(5, chunks[1].payload[0].toInt())
        assertEquals(1, chunks[1].payload[1].toInt())
        assertEquals(100, java.nio.ByteBuffer.wrap(chunks[1].payload, 2, 4).int)
        assertArrayEquals(data.copyOfRange(200, 250), chunks[2].payload.copyOfRange(6, chunks[2].payload.size))

        uploader.onMessage(link, Incoming(0x28, 0x07, Tlv().put(0x01, 0x05.toByte())))
        assertEquals(UploadState.Done("Face"), uploader.state.value)
    }
}
