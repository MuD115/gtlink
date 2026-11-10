package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Crypto
import com.nmnd.gtlink.protocol.Packet
import com.nmnd.gtlink.protocol.Tlv
import com.nmnd.gtlink.protocol.hexToBytes
import com.nmnd.gtlink.protocol.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer

/** Per-watch values that must survive restarts, or the watch stops recognising the phone. */
interface PairingStore {
    /** The long-term key the watch issued at pairing (HiChain) or that we chose (legacy). */
    var authKey: ByteArray?
    /** Our identity towards the watch: 32 upper-case hex chars, generated once. */
    val androidId: String
    /** A made-up MAC (FF:FF:FF:xx:xx:xx) the legacy bond uses as our serial. */
    val fakeMac: String
}

/**
 * Brings a freshly opened link to an authenticated, encrypted session.
 *
 * The flow mirrors Gadgetbridge's HuaweiSupportProvider.initializeDevice*:
 *  1. link parameters (slice size, auth version, nonce),
 *  2. device status,
 *  3. security negotiation, which picks HiChain (GT 4 and other HarmonyOS watches),
 *     HiChain Lite, or the legacy challenge/response,
 *  4. the chosen handshake, which leaves a session key in [WatchLink.params].
 */
class Authenticator(
    private val link: WatchLink,
    private val log: ProtocolLog,
    private val store: PairingStore,
    private val watchMac: String,
    private val phoneModel: String,
    private val status: (String) -> Unit,
) {
    private val params get() = link.params

    suspend fun run() {
        status("Reading link parameters")
        readLinkParams()

        val deviceStatus = link.request(Packet(0x01, 0x16, Tlv().put(0x01), encrypt = false, sliced = true))
            ?.let { if (it.contains(0x01)) it.byte(0x01).toInt() else -1 } ?: -1
        log.add("device status $deviceStatus")

        val type = params.deviceSupportType
        val hiChain = type == 0x01 || type == 0x02 || type == 0x03 || type == 0x04
        if (!hiChain) {
            status("Authenticating")
            legacyAuth(lite = false)
            return
        }

        params.authMode = if (type == 0x04) 4 else 2
        val negotiation = link.requireReply(securityNegotiation())
        var authType = -1
        var pw = -1
        if (negotiation.contains(0x01)) {
            if (negotiation.byte(0x01).toInt() == 0x01) authType = 0x0186A0
            if (negotiation.byte(0x01).toInt() == 0x04) pw = 4
        }
        if (negotiation.contains(0x02)) {
            authType = negotiation.byte(0x02).toInt()
            if (pw != -1) authType = authType xor pw
        }
        if (negotiation.contains(0x7F)) authType = negotiation.byte(0x7F).toInt()
        log.add("negotiated authType $authType")

        val isHiChain3 = (authType xor 0x01) == 0x04 || (authType xor 0x02) == 0x04
        when {
            authType == 0x0186A0 || isHiChain3 -> hiChain()
            authType == 0x01 || authType == 0x02 -> legacyAuth(lite = true)
            else -> throw WatchException("The watch refused to negotiate a pairing method (code $authType)")
        }
    }

    private suspend fun readLinkParams() {
        val reply = link.requireReply(
            Packet(0x01, 0x01, Tlv().put(0x01).put(0x02).put(0x03).put(0x04), encrypt = false, sliced = false),
        )
        if (reply.contains(0x01)) params.protocolVersion = reply.byte(0x01).toInt()
        if (reply.contains(0x02)) params.sliceSize = reply.short(0x02).toInt() and 0xFFFF
        if (reply.contains(0x03)) params.mtu = reply.short(0x03).toInt() and 0xFFFF
        val auth = reply.bytes(0x05)
        params.authVersion = auth[1].toInt()
        params.serverNonce = auth.copyOfRange(2, 18)
        if (reply.contains(0x07)) params.deviceSupportType = reply.byte(0x07).toInt()
        if (reply.contains(0x08)) params.authAlgo = reply.byte(0x08).toInt()
        if (reply.contains(0x09)) params.bondState = reply.byte(0x09).toInt()
        if (reply.contains(0x0C)) params.encryptMethod = reply.byte(0x0C).toInt()
    }

    private fun securityNegotiation(): Packet {
        val mode = params.authMode
        val t = Tlv().put(0x01, mode.toByte())
        if (mode == 0x02 || mode == 0x04) t.put(0x02, 0x01.toByte())
        t.put(0x05, store.androidId.toByteArray(Charsets.UTF_8))
            .put(0x03, 0x01.toByte())
            .put(0x04, 0x00.toByte())
        if (mode == 0x04) t.put(0x06).put(0x07, phoneModel)
        if (params.encryptMethod == 1) t.put(0x0D, 0x01.toByte())
        return Packet(0x01, 0x33, t, encrypt = false)
    }

    private suspend fun fetchPinCode() {
        val reply = link.requireReply(Packet(0x01, 0x2C, Tlv().put(0x01), encrypt = false))
        params.pinCode = Crypto.decryptPinCode(params.authVersion, params.encryptMethod, reply.bytes(0x01), reply.bytes(0x02))
    }

    // --- HiChain (GT 4) ---------------------------------------------------------

    private suspend fun hiChain() {
        val stored = store.authKey
        if (stored == null) {
            status("Confirm the pairing on your watch")
            fetchPinCode()
            HiChainSession(operation = 1).run(timeoutMs = 30_000)
        }
        status("Binding")
        try {
            HiChainSession(operation = 2).run(timeoutMs = 8_000)
        } catch (e: WatchException) {
            if (stored == null) throw e
            // The watch has forgotten us (reset, or paired elsewhere): pair from scratch.
            store.authKey = null
            status("Confirm the pairing on your watch")
            fetchPinCode()
            HiChainSession(operation = 1).run(timeoutMs = 30_000)
            HiChainSession(operation = 2).run(timeoutMs = 8_000)
        }
    }

    /**
     * One HiChain operation: 1 = authenticate with the PIN and receive a long-term
     * key, 2 = bind with that key and derive the session key. Port of Gadgetbridge's
     * GetHiChainRequest.
     */
    private inner class HiChainSession(private val operation: Int) {
        private val requestId = System.currentTimeMillis()
        private val selfAuthId = store.androidId.toByteArray(Charsets.UTF_8)
        private lateinit var seed: ByteArray
        private lateinit var randSelf: ByteArray
        private lateinit var randPeer: ByteArray
        private lateinit var peerAuthId: ByteArray
        private lateinit var psk: ByteArray
        private lateinit var sessionKey: ByteArray
        private var challenge: ByteArray? = null

        suspend fun run(timeoutMs: Long) {
            // Step 1: exchange salts, check the watch's token.
            seed = Crypto.randomBytes(32)
            randSelf = Crypto.randomBytes(16)
            var reply = exchange(1, timeoutMs) {
                put("isoSalt", randSelf.toHex())
                put("peerAuthId", selfAuthId.toHex())
                put("operationCode", operation)
                put("seed", seed.toHex())
                put("peerUserType", 0)
                if (operation == 2) {
                    put("pkgName", "com.huawei.devicegroupmanage")
                    put("serviceType", GROUP_ID)
                    put("keyLength", 0x20)
                }
            }
            randPeer = reply.hex("isoSalt")
            peerAuthId = reply.hex("peerAuthId")
            val key = if (operation == 1) {
                Crypto.sha256(params.pinCode!!.toHex().toByteArray(Charsets.UTF_8))
            } else {
                store.authKey ?: throw WatchException("No pairing key stored")
            }
            psk = Crypto.hmacSha256(key, seed)
            val expectedToken = Crypto.hmacSha256(psk, randPeer + randSelf + selfAuthId + peerAuthId)
            if (!expectedToken.contentEquals(reply.hex("token"))) {
                throw WatchException("Pairing check failed: the watch's token did not match")
            }

            // Step 2: prove we know the PSK.
            val selfToken = Crypto.hmacSha256(psk, randSelf + randPeer + peerAuthId + selfAuthId)
            reply = exchange(2, timeoutMs) {
                put("peerAuthId", selfAuthId.toHex())
                put("token", selfToken.toHex())
            }
            val expectedMac = Crypto.hmacSha256(psk, byteArrayOf(0, 0, 0, 0))
            if (!expectedMac.contentEquals(reply.hex("returnCodeMac"))) {
                throw WatchException("Pairing check failed: return code MAC mismatch")
            }

            sessionKey = Crypto.hkdfSha256(psk, randSelf + randPeer, "hichain_iso_session_key".toByteArray(), 32)

            if (operation == 1) {
                // Step 3: the watch hands over the long-term auth token.
                val nonce = Crypto.randomBytes(12)
                val ch = Crypto.randomBytes(16)
                challenge = ch
                val encData = Crypto.aesGcmEncrypt(ch, sessionKey, nonce, "hichain_iso_exchange".toByteArray())
                reply = exchange(3, timeoutMs) {
                    put("nonce", nonce.toHex())
                    put("encData", encData.toHex())
                }
                val authToken = Crypto.aesGcmDecrypt(reply.hex("encAuthToken"), sessionKey, reply.hex("nonce"), ch)
                store.authKey = authToken
            }

            // Final step: confirm the result. For bind it carries message id 3.
            val nonce = Crypto.randomBytes(12)
            val encResult = Crypto.aesGcmEncrypt(byteArrayOf(0, 0, 0, 0), sessionKey, nonce, "hichain_iso_result".toByteArray())
            sendStep(if (operation == 1) 4 else 3, timeoutMs, expectJson = false) {
                put("nonce", nonce.toHex())
                put("encResult", encResult.toHex())
                put("operationCode", operation)
            }

            if (operation == 2) {
                params.secretKey = Crypto.hkdfSha256(sessionKey, randSelf + randPeer, "hichain_return_key".toByteArray(), 32)
            }
        }

        private suspend fun exchange(messageId: Int, timeoutMs: Long, payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            sendStep(messageId, timeoutMs, expectJson = true, payload)!!

        private suspend fun sendStep(
            messageId: Int,
            timeoutMs: Long,
            expectJson: Boolean,
            payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ): JsonObject? {
            val json = buildJsonObject {
                put("authForm", 0)
                putJsonObject("payload") {
                    putJsonObject("version") {
                        put("minVersion", "1.0.0")
                        put("currentVersion", "2.0.16")
                    }
                    payload()
                }
                put("groupAndModuleVersion", "2.0.1")
                put("message", if (operation == 2) messageId or 0x10 else messageId)
                if (operation == 1) {
                    put("requestId", requestId.toString())
                    put("groupId", GROUP_ID)
                    put("groupName", "health_group_name")
                    put("groupOp", 2)
                    put("groupType", 256)
                    put("peerDeviceId", store.androidId)
                    put("connDeviceId", store.androidId)
                    put("appId", "com.huawei.health")
                    put("ownerName", "")
                }
                if (operation == 2 && messageId <= 2) put("isDeviceLevel", false)
            }
            val t = Tlv()
                .put(0x01, json.toString())
                .put(0x02, operation.toByte())
                .put(0x03, ByteBuffer.allocate(8).putLong(requestId).array())
            val reply = link.requireReply(Packet(0x01, 0x28, t, encrypt = false, sliced = true), timeoutMs)
            if (!reply.contains(0x04)) throw WatchException("Unexpected HiChain reply framing")
            if (reply.byte(0x04).toInt() != 0x00) {
                if (expectJson) throw WatchException("The watch ended pairing early")
                return null
            }
            val envelope = Json.parseToJsonElement(reply.string(0x01)).jsonObject
            val body = envelope["payload"]?.jsonObject ?: throw WatchException("HiChain reply without payload")
            val error = body["errorCode"]?.jsonPrimitive?.int ?: 0
            if (error != 0) throw WatchException("The watch rejected pairing (error $error)")
            return body
        }

        private fun JsonObject.hex(key: String): ByteArray =
            (this[key]?.jsonPrimitive?.content ?: throw WatchException("HiChain reply missing $key")).hexToBytes()
    }

    // --- HiChain Lite and legacy ------------------------------------------------

    private suspend fun legacyAuth(lite: Boolean) {
        if (lite && params.pinCode == null && params.authVersion != 0x02) {
            status("Confirm the pairing on your watch")
            fetchPinCode()
        }
        val authKey = store.authKey ?: Crypto.randomBytes(16).also { store.authKey = it }

        val clientNonce = Crypto.randomBytes(16)
        val doubleNonce = params.serverNonce + clientNonce
        var key: ByteArray? = null
        if (params.authMode == 0x02) key = if (params.authVersion == 0x02) authKey else params.pinCode
        val digest = Crypto.digestChallenge(params.authVersion, params.authAlgo, params.authMode, key, doubleNonce)
            ?: throw WatchException("Missing pairing PIN")
        params.firstKey = digest.copyOfRange(32, 48)
        val nonce = ByteBuffer.allocate(18).putShort(params.authVersion.toShort()).put(clientNonce).array()
        val t = Tlv().put(0x01, digest.copyOf(32)).put(0x02, nonce)
        if (params.authMode == 0x02) t.put(0x03, params.authAlgo.toByte())
        val reply = link.requireReply(Packet(0x01, 0x13, t, encrypt = false), if (lite) 30_000 else 5_000)
        val expected = Crypto.digestResponse(params.authVersion, params.authAlgo, params.authMode, key, doubleNonce)!!.copyOf(32)
        if (!expected.contentEquals(reply.bytes(0x01))) throw WatchException("The watch's answer to our challenge was wrong")

        // Only the BLE family and HiChain Lite bond; the GT 4's BR link stops here.
        params.secretKey = authKey
        if (!lite) return

        params.secretKey = null
        val serial = store.fakeMac.replace(":", "").substring(6, 12).toByteArray(Charsets.UTF_8)
        val bondParams = link.requireReply(
            Packet(
                0x01, 0x0F,
                Tlv().put(0x01).put(0x03, serial).put(0x04, 0x02.toByte()).put(0x05)
                    .put(0x07, store.fakeMac.toByteArray(Charsets.UTF_8)).put(0x09),
                encrypt = false,
            ),
        )
        params.encryptionCounter = bondParams.int(0x09).toLong() and 0xFFFFFFFFL
        val iv = params.nextIv()
        val encryptionKey = params.firstKey!!
        val bondKey = Crypto.encrypt(params.encryptMethod == 0x01, authKey, encryptionKey, iv)
        link.request(
            Packet(
                0x01, 0x0E,
                Tlv().put(0x01).put(0x03, 0x00.toByte()).put(0x05, serial).put(0x06, bondKey).put(0x07, iv),
                encrypt = false,
            ),
        )
        params.secretKey = authKey
    }

    companion object {
        const val GROUP_ID = "7B0BC0CBCE474F6C238D9661C63400B797B166EA7849B3A370FC73A9A236E989"
    }
}
