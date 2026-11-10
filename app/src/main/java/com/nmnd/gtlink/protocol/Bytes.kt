package com.nmnd.gtlink.protocol

import java.nio.ByteBuffer

private val HEX = "0123456789ABCDEF".toCharArray()

/** Upper-case hex, exactly as the watch's auth JSON expects (it compares strings). */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    val s = if (length % 2 == 1) "0$this" else this
    return ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }
}

fun Int.toBytes(): ByteArray = ByteBuffer.allocate(4).putInt(this).array()
fun Short.toBytes(): ByteArray = ByteBuffer.allocate(2).putShort(this).array()
fun Long.toBytes(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()

/**
 * The CRC the Huawei link layer appends to every frame: CRC-16/XMODEM-style, seeded
 * with 0x0000. Ported from Gadgetbridge's CheckSums.getCRC16.
 */
object Crc16 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, seed: Int = 0): Int {
        var crc = seed
        for (i in offset until offset + length) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xFFFF
            crc = crc xor (data[i].toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) shr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc and 0xFFFF
    }
}
