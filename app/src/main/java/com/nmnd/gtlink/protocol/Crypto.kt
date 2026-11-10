package com.nmnd.gtlink.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives and key derivations of the Huawei link protocol.
 *
 * The constants and derivations are ported from Gadgetbridge's HuaweiCrypto /
 * CryptoUtils (AGPL-3.0). Nothing here is invented: every byte has to match what
 * the watch firmware computes, or the handshake simply fails.
 */
object Crypto {
    private val random = SecureRandom()

    private val SECRET_KEY_1_V1 = bytes(0x6F, 0x75, 0x6A, 0x79, 0x6D, 0x77, 0x71, 0x34, 0x63, 0x6C, 0x76, 0x39, 0x33, 0x37, 0x38, 0x79)
    private val SECRET_KEY_2_V1 = bytes(0x62, 0x31, 0x30, 0x6A, 0x67, 0x66, 0x64, 0x39, 0x79, 0x37, 0x76, 0x73, 0x75, 0x64, 0x61, 0x39)
    private val SECRET_KEY_1_V23 = bytes(0x55, 0x53, 0x86, 0xFC, 0x63, 0x20, 0x07, 0xAA, 0x86, 0x49, 0x35, 0x22, 0xB8, 0x6A, 0xE2, 0x5C)
    private val SECRET_KEY_2_V23 = bytes(0x33, 0x07, 0x9B, 0xC5, 0x7A, 0x88, 0x6D, 0x3C, 0xF5, 0x61, 0x37, 0x09, 0x6F, 0x22, 0x80, 0x00)

    private val DIGEST_SECRET_V1 = bytes(0x70, 0xFB, 0x6C, 0x24, 0x03, 0x5F, 0xDB, 0x55, 0x2F, 0x38, 0x89, 0x8A, 0xEE, 0xDE, 0x3F, 0x69)
    private val DIGEST_SECRET_V2 = bytes(0x93, 0xAC, 0xDE, 0xF7, 0x6A, 0xCB, 0x09, 0x85, 0x7D, 0xBF, 0xE5, 0x26, 0x1A, 0xAB, 0xCD, 0x78)
    private val DIGEST_SECRET_V3 = bytes(0x9C, 0x27, 0x63, 0xA9, 0xCC, 0xE1, 0x34, 0x76, 0x6D, 0xE3, 0xFF, 0x61, 0x18, 0x20, 0x05, 0x53)

    private val MESSAGE_RESPONSE = bytes(0x01, 0x10)
    private val MESSAGE_CHALLENGE = bytes(0x01, 0x00)
    private val MESSAGE_CHALLENGE_V4 = bytes(0x04, 0x00)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // --- primitives -------------------------------------------------------------

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, secret)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteBuffer.allocate(((length + 31) / 32) * 32)
        var previous = ByteArray(0)
        var round = 1
        while (out.position() < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(round.toByte())
            previous = mac.doFinal()
            out.put(previous)
            round++
        }
        return out.array().copyOf(length)
    }

    fun pbkdf2Sha256(password: String, salt: String, iterations: Int, bits: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, bits))
            .encoded

    fun aesGcmEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            if (aad != null) updateAAD(aad)
            doFinal(data)
        }

    fun aesGcmDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            if (aad != null) updateAAD(aad)
            doFinal(data)
        }

    fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun encrypt(useGcm: Boolean, data: ByteArray, key: ByteArray, iv: ByteArray) =
        if (useGcm) aesGcmEncrypt(data, key, iv) else aesCbcEncrypt(data, key, iv)

    fun decrypt(useGcm: Boolean, data: ByteArray, key: ByteArray, iv: ByteArray) =
        if (useGcm) aesGcmDecrypt(data, key, iv) else aesCbcDecrypt(data, key, iv)

    // --- link-layer derivations -------------------------------------------------

    /**
     * IV for the counter-based (CBC) mode: 12 random bytes followed by the next value of
     * a 32-bit counter the watch tracks. Returns the IV and the counter it consumed.
     */
    fun counterIv(counter: Long): Pair<ByteArray, Long> {
        val next = if (counter == 0xFFFFFFFFL) 1L else counter + 1
        val iv = ByteBuffer.allocate(16).put(randomBytes(12)).putInt(next.toInt()).array()
        return iv to next
    }

    private fun digestSecret(authVersion: Int): ByteArray = when (authVersion) {
        1, 4 -> DIGEST_SECRET_V1.clone()
        2 -> DIGEST_SECRET_V2.clone()
        else -> DIGEST_SECRET_V3.clone()
    }

    private fun computeDigest(authVersion: Int, message: ByteArray, nonce: ByteArray): ByteArray {
        val step1 = hmacSha256(digestSecret(authVersion) + message, nonce)
        return hmacSha256(step1, nonce) + step1
    }

    private fun computeDigestHiChainLite(authVersion: Int, authAlgo: Int, message: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val hashKey = sha256(key.toHex().toByteArray(Charsets.UTF_8))
        val secret = digestSecret(authVersion)
        for (i in secret.indices) secret[i] = (secret[i].toInt() xor hashKey[i].toInt()).toByte()
        val msg = secret + message
        val step1 = if (authAlgo == 0x01) {
            pbkdf2Sha256(msg.toHex(), nonce.toHex(), 1000, 256)
        } else {
            hmacSha256(msg, nonce)
        }
        return hmacSha256(step1, nonce) + step1
    }

    /** 64 bytes: the first 32 are sent/checked, the next 16 become the HiChain Lite bond key. */
    fun digestChallenge(authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray): ByteArray? =
        digest(authVersion, authAlgo, authMode, key, nonce, if (authVersion == 4) MESSAGE_CHALLENGE_V4 else MESSAGE_CHALLENGE, MESSAGE_CHALLENGE)

    fun digestResponse(authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray): ByteArray? =
        digest(authVersion, authAlgo, authMode, key, nonce, MESSAGE_RESPONSE, MESSAGE_RESPONSE)

    private fun digest(
        authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray,
        plainMessage: ByteArray, liteMessage: ByteArray,
    ): ByteArray? {
        if (authMode == 0x02) {
            if (key == null) return null
            if (authVersion == 0x02) {
                val k = key + liteMessage
                return hmacSha256(k, nonce) + k
            }
            return computeDigestHiChainLite(authVersion, authAlgo, liteMessage, key, nonce)
        }
        return computeDigest(authVersion, plainMessage, nonce)
    }

    /** The bond-encryption key for non-HiChain devices, derived from the watch's MAC address. */
    fun createSecretKey(authVersion: Int, macAddress: String): ByteArray {
        val k1 = if (authVersion == 1) SECRET_KEY_1_V1 else SECRET_KEY_1_V23
        val k2 = if (authVersion == 1) SECRET_KEY_2_V1 else SECRET_KEY_2_V23
        val macKey = (macAddress.replace(":", "") + "0000").toByteArray(Charsets.UTF_8)
        val mixed = ByteArray(16) { i -> ((((k1[i].toInt() and 0xFF) shl 4) xor (k2[i].toInt() and 0xFF)) and 0xFF).toByte() }
        val mixedHash = sha256(mixed)
        val finalMixed = ByteArray(16) { i -> ((((mixedHash[i].toInt() and 0xFF) shr 6) xor (macKey[i].toInt() and 0xFF)) and 0xFF).toByte() }
        return sha256(finalMixed).copyOf(16)
    }

    /** The watch sends its pairing PIN encrypted with the static digest secret. */
    fun decryptPinCode(authVersion: Int, encryptMethod: Int, message: ByteArray, iv: ByteArray): ByteArray =
        decrypt(encryptMethod == 1, message, digestSecret(authVersion), iv)
}
