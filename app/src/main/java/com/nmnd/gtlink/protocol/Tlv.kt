package com.nmnd.gtlink.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MissingTagException(tag: Int) : Exception("Missing tag 0x" + Integer.toHexString(tag))

/**
 * Huawei's tag-length-value container. Tags are one byte, lengths are a big-endian
 * base-128 varint, and the same tag may appear more than once (lists are repeated
 * tags). A tag with the high bit set (0x80+) holds a nested container.
 */
class Tlv {
    class Item(val tag: Int, val value: ByteArray)

    val items = mutableListOf<Item>()

    fun put(tag: Int): Tlv = apply { items += Item(tag and 0xFF, ByteArray(0)) }
    fun put(tag: Int, value: ByteArray?): Tlv = apply { if (value != null) items += Item(tag and 0xFF, value) }
    fun put(tag: Int, value: Byte): Tlv = put(tag, byteArrayOf(value))
    fun put(tag: Int, value: Boolean): Tlv = put(tag, byteArrayOf(if (value) 1 else 0))
    fun put(tag: Int, value: Short): Tlv = put(tag, value.toBytes())
    fun put(tag: Int, value: Int): Tlv = put(tag, value.toBytes())
    fun put(tag: Int, value: Long): Tlv = put(tag, value.toBytes())
    fun put(tag: Int, value: String?): Tlv = if (value == null) this else put(tag, value.toByteArray(Charsets.UTF_8))
    fun put(tag: Int, value: Tlv): Tlv = put(tag, value.serialize())

    fun contains(tag: Int) = items.any { it.tag == (tag and 0xFF) }

    fun bytes(tag: Int): ByteArray = items.firstOrNull { it.tag == (tag and 0xFF) }?.value ?: throw MissingTagException(tag)
    fun bytesOrNull(tag: Int): ByteArray? = items.firstOrNull { it.tag == (tag and 0xFF) }?.value
    fun byte(tag: Int): Byte = bytes(tag)[0]
    fun boolean(tag: Int): Boolean = bytes(tag)[0].toInt() == 1
    fun short(tag: Int): Short = ByteBuffer.wrap(bytes(tag)).short
    fun int(tag: Int): Int = ByteBuffer.wrap(bytes(tag)).int
    fun string(tag: Int): String = String(bytes(tag), Charsets.UTF_8)
    fun obj(tag: Int): Tlv = Tlv().parse(bytes(tag))
    fun objects(tag: Int): List<Tlv> = items.filter { it.tag == (tag and 0xFF) }.map { Tlv().parse(it.value) }

    /** Reads a 1-, 2- or 4-byte unsigned big-endian number, as the watch sizes them freely. */
    fun asInt(tag: Int, default: Int): Int {
        val b = bytesOrNull(tag) ?: return default
        if (b.isEmpty() || b.size > 4) return default
        var res = 0
        for (x in b) res = (res shl 8) or (x.toInt() and 0xFF)
        return res
    }

    fun parse(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Tlv {
        var parsed = 0
        while (parsed < length) {
            val tag = buffer[offset + parsed].toInt() and 0xFF
            parsed += 1
            // Encrypted payloads can carry a trailing null byte; it isn't an element.
            if (parsed == length && tag == 0) break
            var size = 0
            while (true) {
                val b = buffer[offset + parsed].toInt()
                parsed += 1
                size = (size shl 7) + (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            if (size < 0 || size > length - parsed) {
                throw IndexOutOfBoundsException("TLV element size $size exceeds remaining ${length - parsed}")
            }
            items += Item(tag, buffer.copyOfRange(offset + parsed, offset + parsed + size))
            parsed += size
        }
        return this
    }

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) {
            out.write(item.tag)
            out.write(varInt(item.value.size))
            out.write(item.value)
        }
        return out.toByteArray()
    }

    override fun toString(): String =
        if (items.isEmpty()) "Empty" else items.joinToString(" ") { "{%02x:%s}".format(it.tag, it.value.toHex()) }

    companion object {
        fun varInt(value: Int): ByteArray {
            var size = 0
            var v = value
            do { size++; v = v ushr 7 } while (v != 0)
            val out = ByteArray(size)
            v = value
            out[size - 1] = (v and 0x7F).toByte()
            for (i in size - 2 downTo 0) {
                v = v ushr 7
                out[i] = ((v and 0x7F) or 0x80).toByte()
            }
            return out
        }
    }
}

fun tlv(block: Tlv.() -> Unit): Tlv = Tlv().apply(block)
