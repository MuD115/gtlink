package com.nmnd.gtlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte vectors taken from Gadgetbridge's TestHuaweiPacket, which were checked against real devices. */
class FrameTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun unencryptedUnsliced() {
        val params = LinkParams()
        val out = Frame.encode(Packet(0x01, 0x02, Tlv().put(0x03, 0x05060708), encrypt = false, sliced = false), params)
        assertEquals(1, out.size)
        assertArrayEquals(b(0x5a, 0x00, 0x09, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0xA4, 0xF0), out[0])
    }

    @Test
    fun encryptedUnslicedWithZeroKeyAndIv() {
        val params = LinkParams().apply {
            secretKey = ByteArray(16)
            ivOverride = ByteArray(16)
        }
        val out = Frame.encode(Packet(0x01, 0x02, Tlv().put(0x03, 0x05060708), sliced = false), params)
        val expected = b(
            0x5a, 0x00, 0x2a, 0x00, 0x01, 0x02, 0x7c, 0x01, 0x01, 0x7d, 0x10,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x7e, 0x10, 0x3b, 0x89, 0xfc, 0x79, 0xd9, 0x05, 0x5e, 0xed, 0x52, 0x35, 0xfe, 0x16, 0xa0, 0x8a, 0x4d, 0x53, 0x93, 0xc7,
        )
        assertArrayEquals(expected, out[0])
    }

    @Test
    fun slicedIntoThreeFrames() {
        val params = LinkParams().apply { sliceSize = 0x10 }
        val tlv = Tlv().put(0x01, 0).put(0x02, 0).put(0x03, 0).put(0x04, 0)
        val out = Frame.encode(Packet(0x01, 0x02, tlv, encrypt = false, sliced = true), params)
        assertEquals(3, out.size)
        assertArrayEquals(b(0x5a, 0x00, 0x0b, 0x01, 0x00, 0x01, 0x02, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x02, 0xcc, 0x98), out[0])
        assertArrayEquals(b(0x5a, 0x00, 0x0b, 0x02, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x03, 0x04, 0x00, 0x00, 0xfa, 0xd3), out[1])
        assertArrayEquals(b(0x5a, 0x00, 0x0a, 0x03, 0x02, 0x00, 0x00, 0x04, 0x04, 0x00, 0x00, 0x00, 0x00, 0x37, 0xca), out[2])

        // And the reader puts them back together.
        val reader = Frame.Reader()
        val messages = out.flatMap { reader.feed(it) }
        assertEquals(1, messages.size)
        assertEquals(tlv.toString(), Frame.decode(messages[0], params).toString())
    }

    @Test
    fun decryptsKnownFindPhonePacket() {
        val raw = b(
            0x5a, 0x00, 0x2a, 0x00, 0x0b, 0x01, 0x7c, 0x01, 0x01, 0x7d, 0x10,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x7e, 0x10, 0x28, 0x00, 0x99, 0x6f, 0x2a, 0xcb, 0x62, 0x3a, 0xe6, 0x54, 0x28, 0x54, 0xf8, 0xab, 0x54, 0x83, 0xf4, 0xf4,
        )
        val params = LinkParams().apply { secretKey = ByteArray(16) }
        val messages = Frame.Reader().feed(raw)
        assertEquals(1, messages.size)
        assertEquals(0x0b, messages[0].service)
        assertEquals(0x01, messages[0].command)
        val tlv = Frame.decode(messages[0], params)!!
        assertEquals(false, tlv.boolean(0x01))
    }

    @Test
    fun readerHandlesSplitAndConcatenatedFrames() {
        val params = LinkParams()
        val a = Frame.encode(Packet(0x01, 0x08, Tlv().put(0x01, 0x42.toByte()), encrypt = false, sliced = false), params)[0]
        val c = Frame.encode(Packet(0x0b, 0x01, Tlv().put(0x01, true), encrypt = false, sliced = false), params)[0]
        val stream = byteArrayOf(0x00, 0x13) + a + c // leading garbage too
        val reader = Frame.Reader()
        val got = reader.feed(stream.copyOfRange(0, 5)) + reader.feed(stream.copyOfRange(5, 12)) + reader.feed(stream.copyOfRange(12, stream.size))
        assertEquals(listOf(0x08, 0x01), got.map { it.command })
        assertEquals(0, reader.crcErrors)
    }

    @Test
    fun tlvVarIntLengths() {
        val long = ByteArray(300) { it.toByte() }
        val bytes = Tlv().put(0x10, long).put(0x11).serialize()
        // 300 = 0b10_0101100 -> 0x82 0x2C
        assertEquals(0x82.toByte(), bytes[1])
        assertEquals(0x2C.toByte(), bytes[2])
        val parsed = Tlv().parse(bytes)
        assertArrayEquals(long, parsed.bytes(0x10))
        assertEquals(0, parsed.bytes(0x11).size)
    }

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()
        val okm = Crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3CB25F25FAACD57A90434F64D0362F2A2D2D0A90CF1A5A4C5DB02D56ECC4C5BF34007208D5B887185865",
            okm.toHex(),
        )
    }
}
