package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Crypto
import com.nmnd.gtlink.protocol.Frame
import com.nmnd.gtlink.protocol.LinkParams
import com.nmnd.gtlink.protocol.Packet
import com.nmnd.gtlink.protocol.Tlv
import com.nmnd.gtlink.protocol.hexToBytes
import com.nmnd.gtlink.protocol.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private class MemoryStore : PairingStore {
    override var authKey: ByteArray? = null
    override val androidId: String = Crypto.randomBytes(16).toHex()
    override val fakeMac: String = "FF:FF:FF:12:34:56"
}

/**
 * The watch's side of the handshake, written from the phone-side code's checks.
 * It proves the phone half is internally consistent (every MAC, key and AAD lines
 * up end to end); it can't prove the firmware agrees, which only a real watch can.
 */
private class FakeWatch(private val deviceSupportType: Int, private val authVersion: Int = 0x02) {
    val params = LinkParams().apply { encryptMethod = 0x01; this.deviceSupportType = this@FakeWatch.deviceSupportType }
    lateinit var phone: WatchLink
    private val reader = Frame.Reader()
    private val serverNonce = Crypto.randomBytes(16)
    private val pin = "8462".toByteArray()
    private val watchId = "WATCH-GT4-0001".toByteArray()
    var authToken: ByteArray? = null
    var sessionKeyFinal: ByteArray? = null
    var forgetPhone = false

    // per-operation state
    private var psk = ByteArray(0)
    private var randSelf = ByteArray(0) // phone's salt
    private var randPeer = ByteArray(0) // ours
    private var phoneId = ByteArray(0)
    private var sessionKey = ByteArray(0)

    fun receive(bytes: ByteArray) {
        for (m in reader.feed(bytes)) {
            val tlv = Frame.decode(m, params)!!
            val reply = handle(m.service, m.command, tlv) ?: continue
            for (f in Frame.encode(reply, params)) phone.onBytes(f)
        }
    }

    private fun handle(service: Int, command: Int, t: Tlv): Packet? = when (service to command) {
        0x01 to 0x01 -> Packet(
            0x01, 0x01,
            Tlv().put(0x01, 0x02.toByte()).put(0x02, 0x00F4.toShort()).put(0x03, 0x0400.toShort())
                .put(0x05, byteArrayOf(0x00, authVersion.toByte()) + serverNonce)
                .put(0x07, deviceSupportType.toByte()).put(0x08, 0x01.toByte()).put(0x0C, 0x01.toByte()),
            encrypt = false, sliced = false,
        )
        0x01 to 0x16 -> Packet(0x01, 0x16, Tlv().put(0x01, 0x01.toByte()), encrypt = false)
        0x01 to 0x33 -> Packet(0x01, 0x33, Tlv().put(0x01, 0x01.toByte()), encrypt = false)
        0x01 to 0x2C -> {
            val iv = Crypto.randomBytes(12)
            // Digest secret v2 (auth version 2), GCM because encryptMethod = 1.
            val secret = "93ACDEF76ACB09857DBFE5261AABCD78".hexToBytes()
            Packet(0x01, 0x2C, Tlv().put(0x01, Crypto.aesGcmEncrypt(pin, secret, iv)).put(0x02, iv), encrypt = false)
        }
        0x01 to 0x28 -> hiChain(t)
        0x01 to 0x13 -> {
            val nonce = t.bytes(0x02)
            val clientNonce = nonce.copyOfRange(2, 18)
            val answer = Crypto.digestResponse(authVersion, 0x01, 0, null, serverNonce + clientNonce)!!.copyOf(32)
            Packet(0x01, 0x13, Tlv().put(0x01, answer), encrypt = false)
        }
        0x01 to 0x08 -> Packet(0x01, 0x08, Tlv().put(0x01, 0x55.toByte()))
        else -> null
    }

    private fun hiChain(t: Tlv): Packet? {
        val json = Json.parseToJsonElement(t.string(0x01)).jsonObject
        val payload = json["payload"]!!.jsonObject
        val op = t.byte(0x02).toInt()
        val message = json["message"]!!.jsonPrimitive.int and 0x0F
        fun hex(k: String) = payload[k]!!.jsonPrimitive.content.hexToBytes()

        return when (message) {
            1 -> {
                if (op == 2 && forgetPhone) return reply(op, buildJsonObject { put("errorCode", 2) })
                randSelf = hex("isoSalt")
                phoneId = hex("peerAuthId")
                val key = if (op == 1) Crypto.sha256(pin.toHex().toByteArray()) else authToken!!
                psk = Crypto.hmacSha256(key, hex("seed"))
                randPeer = Crypto.randomBytes(16)
                val token = Crypto.hmacSha256(psk, randPeer + randSelf + phoneId + watchId)
                reply(op, buildJsonObject {
                    put("isoSalt", randPeer.toHex())
                    put("peerAuthId", watchId.toHex())
                    put("peerUserType", 0)
                    put("token", token.toHex())
                })
            }
            2 -> {
                val expected = Crypto.hmacSha256(psk, randSelf + randPeer + watchId + phoneId)
                check(expected.contentEquals(hex("token"))) { "phone token wrong" }
                sessionKey = Crypto.hkdfSha256(psk, randSelf + randPeer, "hichain_iso_session_key".toByteArray(), 32)
                reply(op, buildJsonObject { put("returnCodeMac", Crypto.hmacSha256(psk, ByteArray(4)).toHex()) })
            }
            3 -> if (op == 1) {
                val challenge = Crypto.aesGcmDecrypt(hex("encData"), sessionKey, hex("nonce"), "hichain_iso_exchange".toByteArray())
                val token = Crypto.randomBytes(32)
                authToken = token
                val nonce = Crypto.randomBytes(12)
                reply(op, buildJsonObject {
                    put("nonce", nonce.toHex())
                    put("encAuthToken", Crypto.aesGcmEncrypt(token, sessionKey, nonce, challenge).toHex())
                })
            } else {
                finish(op, payload)
            }
            4 -> finish(op, payload)
            else -> error("unexpected message $message")
        }
    }

    private fun finish(op: Int, payload: JsonObject): Packet {
        val result = Crypto.aesGcmDecrypt(
            payload["encResult"]!!.jsonPrimitive.content.hexToBytes(), sessionKey,
            payload["nonce"]!!.jsonPrimitive.content.hexToBytes(), "hichain_iso_result".toByteArray(),
        )
        check(result.contentEquals(ByteArray(4)))
        if (op == 2) {
            sessionKeyFinal = Crypto.hkdfSha256(sessionKey, randSelf + randPeer, "hichain_return_key".toByteArray(), 32)
        }
        return Packet(0x01, 0x28, Tlv().put(0x04, 0x01.toByte()).put(0x01, "done"), encrypt = false)
    }

    private fun reply(op: Int, body: JsonObject): Packet {
        val json = buildJsonObject {
            put("message", 0x8000 or op)
            putJsonObject("payload") { body.forEach { (k, v) -> put(k, v) } }
        }
        return Packet(0x01, 0x28, Tlv().put(0x01, json.toString()).put(0x04, 0x00.toByte()), encrypt = false)
    }
}

class HandshakeTest {
    private fun connect(watch: FakeWatch, store: PairingStore): WatchLink {
        val log = ProtocolLog()
        val link = WatchLink(LinkParams(), log) { watch.receive(it) }
        watch.phone = link
        runBlocking { Authenticator(link, log, store, "AA:BB:CC:DD:EE:FF", "Pixel") {}.run() }
        // From here on the fake watch encrypts with the session key the phone derived.
        watch.params.secretKey = link.params.secretKey
        return link
    }

    @Test
    fun hiChainFirstPairingThenReconnect() {
        val watch = FakeWatch(deviceSupportType = 0x01)
        val store = MemoryStore()

        val first = connect(watch, store)
        assertNotNull(watch.authToken)
        assertArrayEquals("phone keeps the watch's long-term token", watch.authToken, store.authKey)
        assertArrayEquals("both sides derive the same session key", watch.sessionKeyFinal, first.params.secretKey)

        val battery = runBlocking { first.request(Commands.battery()) }
        assertEquals(0x55, battery!!.asInt(0x01, -1))

        // Second connection: bind only, with the stored key.
        val tokenBefore = watch.authToken
        val second = connect(watch, store)
        assertArrayEquals(tokenBefore, watch.authToken)
        assertArrayEquals(watch.sessionKeyFinal, second.params.secretKey)
    }

    @Test
    fun hiChainRePairsWhenWatchForgotThePhone() {
        val watch = FakeWatch(deviceSupportType = 0x01)
        val store = MemoryStore()
        connect(watch, store)
        val oldToken = store.authKey

        watch.forgetPhone = true
        val log = ProtocolLog()
        val link = WatchLink(LinkParams(), log) { watch.receive(it) }
        watch.phone = link
        // The first bind fails; the watch accepts a fresh pairing afterwards.
        val auth = Authenticator(link, log, store, "AA:BB:CC:DD:EE:FF", "Pixel") { if (it.startsWith("Confirm")) watch.forgetPhone = false }
        runBlocking { auth.run() }
        assertNotNull(link.params.secretKey)
        assert(!oldToken!!.contentEquals(store.authKey!!))
    }

    @Test
    fun legacyChallengeResponse() {
        val watch = FakeWatch(deviceSupportType = 0x00)
        val store = MemoryStore()
        assertNull(store.authKey)
        val link = connect(watch, store)
        assertArrayEquals(store.authKey, link.params.secretKey)
    }
}
