package com.nmnd.gtlink.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Everything the link layer learns during the handshake and needs for every frame
 * afterwards. Gadgetbridge calls this the ParamsProvider.
 */
class LinkParams {
    var sliceSize: Int = 0xF4
    var mtu: Int = 0xFFFF
    var protocolVersion: Int = 0
    var authVersion: Int = 0
    var deviceSupportType: Int = 0
    var authAlgo: Int = 0
    var encryptMethod: Int = 0
    var authMode: Int = 0
    var bondState: Int = 0
    var serverNonce: ByteArray = ByteArray(16)

    /** Session key. Null until authentication completes; nothing is encrypted before that. */
    @Volatile var secretKey: ByteArray? = null
    var firstKey: ByteArray? = null
    var pinCode: ByteArray? = null
    var encryptionCounter: Long = 0

    val useGcm: Boolean get() = encryptMethod == 0x01 || deviceSupportType == 0x04

    /** Tests pin the IV to compare against known ciphertext. */
    internal var ivOverride: ByteArray? = null

    @Synchronized
    fun nextIv(): ByteArray {
        ivOverride?.let { return it }
        if (deviceSupportType == 0x04) return Crypto.randomBytes(16)
        val (iv, counter) = Crypto.counterIv(encryptionCounter)
        encryptionCounter = counter
        return iv
    }
}

/** One protocol message: service + command + TLV body. */
class Packet(
    val service: Int,
    val command: Int,
    val tlv: Tlv = Tlv(),
    /** Most messages are encrypted once a session key exists; auth messages never are. */
    val encrypt: Boolean = true,
    val sliced: Boolean = true,
) {
    override fun toString() = "%02x/%02x %s".format(service, command, tlv)
}

/** A complete message as it came off the wire, before TLV parsing. */
class RawMessage(val service: Int, val command: Int, val payload: ByteArray)

object Frame {
    private const val MAGIC: Byte = 0x5A

    // Messages whose body is not TLV (file and OTA transfer chunks); kept raw.
    private val rawCommands = setOf(0x0a to 0x05, 0x28 to 0x06, 0x2c to 0x05, 0x1c to 0x05)

    fun encode(packet: Packet, params: LinkParams): List<ByteArray> {
        val key = params.secretKey
        val body = if (packet.encrypt && key != null) {
            val iv = params.nextIv()
            Tlv()
                .put(0x7C, 0x01.toByte())
                .put(0x7D, iv)
                .put(0x7E, Crypto.encrypt(params.useGcm, packet.tlv.serialize(), key, iv))
                .serialize()
        } else {
            packet.tlv.serialize()
        }
        return if (packet.sliced) sliced(packet.service, packet.command, body, params.sliceSize)
        else listOf(unsliced(packet.service, packet.command, body))
    }

    /**
     * Frames a non-TLV body, as file-upload chunks are. When [encrypt] is set the body is
     * wrapped in the usual 7C/7D/7E envelope first.
     */
    fun encodeRaw(service: Int, command: Int, body: ByteArray, params: LinkParams, encrypt: Boolean): List<ByteArray> {
        val key = params.secretKey
        val payload = if (encrypt && key != null) {
            val iv = params.nextIv()
            Tlv().put(0x7C, 0x01.toByte()).put(0x7D, iv).put(0x7E, Crypto.encrypt(params.useGcm, body, key, iv)).serialize()
        } else {
            body
        }
        return sliced(service, command, payload, params.sliceSize)
    }

    private fun unsliced(service: Int, command: Int, body: ByteArray): ByteArray {
        val bodyLength = 2 + body.size
        val buf = ByteBuffer.allocate(4 + bodyLength + 2)
        buf.put(MAGIC).putShort((bodyLength + 1).toShort()).put(0)
        buf.put(service.toByte()).put(command.toByte()).put(body)
        val crc = Crc16.compute(buf.array(), 0, 4 + bodyLength)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    private fun sliced(service: Int, command: Int, body: ByteArray, sliceSize: Int): List<ByteArray> {
        val header = 5
        val footer = 2
        val maxBody = sliceSize - header - footer
        val count = Math.ceil((body.size + 2).toDouble() / maxBody).toInt()
        if (count <= 1) return listOf(unsliced(service, command, body))

        val out = mutableListOf<ByteArray>()
        val src = ByteBuffer.wrap(body)
        var slice: Byte = 0x01
        var flag: Byte = 0x00
        for (i in 0 until count) {
            val packetSize = minOf(sliceSize, src.remaining() + header + footer)
            val frame = ByteBuffer.allocate(packetSize)
            var contentSize = packetSize - header - footer
            frame.put(MAGIC).putShort((packetSize - header).toShort())
            if (i == count - 1) slice = 0x03
            frame.put(slice).put(flag)
            flag++
            if (slice == 0x01.toByte()) {
                frame.put(service.toByte()).put(command.toByte())
                slice = 0x02
                contentSize -= 2
            }
            val chunk = ByteArray(minOf(contentSize, src.remaining()))
            src.get(chunk)
            frame.put(chunk)
            val crc = Crc16.compute(frame.array(), 0, frame.position())
            frame.putShort(crc.toShort())
            out += frame.array().copyOf(frame.position())
        }
        return out
    }

    /** Parses the TLV body of a message, decrypting it if it arrived encrypted. */
    fun decode(message: RawMessage, params: LinkParams): Tlv? {
        if ((message.service to message.command) in rawCommands) return null
        val tlv = Tlv().parse(message.payload)
        val key = params.secretKey
        if (key != null && tlv.contains(0x7C) && tlv.boolean(0x7C)) {
            val plain = Crypto.decrypt(params.useGcm, tlv.bytes(0x7E), key, tlv.bytes(0x7D))
            return Tlv().parse(plain)
        }
        return tlv
    }

    /**
     * Turns the byte stream from the socket back into messages: finds frame boundaries,
     * checks each CRC, and joins sliced frames. Bytes can arrive split or concatenated
     * in any way, so state is kept between calls.
     */
    class Reader {
        private var buffer = ByteArray(0)
        private val slices = ByteArrayOutputStream()
        private var inSlices = false

        var crcErrors = 0
            private set

        fun feed(data: ByteArray): List<RawMessage> {
            buffer += data
            val out = mutableListOf<RawMessage>()
            while (true) {
                // Resynchronise on the magic byte if garbage arrived.
                val start = buffer.indexOf(MAGIC)
                if (start < 0) { buffer = ByteArray(0); break }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < 3) break
                val length = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                val total = 3 + length + 2
                if (buffer.size < total) break

                val frame = buffer.copyOfRange(0, total)
                buffer = buffer.copyOfRange(total, buffer.size)

                val expected = ((frame[total - 2].toInt() and 0xFF) shl 8) or (frame[total - 1].toInt() and 0xFF)
                if (Crc16.compute(frame, 0, total - 2) != expected) {
                    crcErrors++
                    inSlices = false
                    slices.reset()
                    continue
                }
                val sliceFlag = frame[3].toInt()
                val contentStart = if (sliceFlag in 1..3) 5 else 4
                val content = frame.copyOfRange(contentStart, total - 2)

                if (sliceFlag in 1..3) {
                    if (sliceFlag == 1) { slices.reset(); inSlices = true }
                    slices.write(content)
                    if (sliceFlag != 3) continue
                    val whole = slices.toByteArray()
                    slices.reset()
                    inSlices = false
                    if (whole.size >= 2) out += RawMessage(whole[0].toInt() and 0xFF, whole[1].toInt() and 0xFF, whole.copyOfRange(2, whole.size))
                } else if (content.size >= 2) {
                    out += RawMessage(content[0].toInt() and 0xFF, content[1].toInt() and 0xFF, content.copyOfRange(2, content.size))
                }
            }
            return out
        }
    }
}
