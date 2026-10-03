/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.cameronpak.muser1.pairing

import java.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class PairingException : Exception()

internal class PairingCrypto(
    private val deviceId: String,
    private val nodeId: String,
    private val mac: String,
    private val version: String = "1.0.0",
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val keyPair: () -> KeyPair = {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    },
    private val nonce: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
) {
    private var rx: ByteArray? = null
    private var tx: ByteArray? = null
    private var session = ""
    private var rxCounter = 0L
    private var txCounter = 0L
    private var deadline = 0L
    private var consent = false
    val confirmed get() = consent && !expired()

    fun reset() { rx = null; tx = null; session = ""; rxCounter = 0; txCounter = 0; deadline = 0; consent = false }

    fun hello(message: JSONObject): JSONObject {
        reset()
        try {
            if (message.opt("version") !is Number || message.getDouble("version") != 5.0 || message.optString("pairing_auth") != "none" ||
                message.optString("pairing_policy") != "confirm_app") throw PairingException()
            val mobilePubText = message.getString("mobile_pub")
            val mobileNonceText = message.getString("mobile_nonce")
            val mobilePub = decode(mobilePubText)
            val mobileNonce = decode(mobileNonceText)
            if (mobilePub.size != 65 || mobilePub[0] != 4.toByte() || mobileNonce.size != 16) throw PairingException()
            val pair = keyPair()
            val params = (pair.public as ECPublicKey).params
            val x = java.math.BigInteger(1, mobilePub.copyOfRange(1, 33))
            val y = java.math.BigInteger(1, mobilePub.copyOfRange(33, 65))
            val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private); agreement.doPhase(peer, true)
            val secret = agreement.generateSecret()
            val point = (pair.public as ECPublicKey).w
            val devicePub = byteArrayOf(4) + unsigned32(point.affineX) + unsigned32(point.affineY)
            val deviceNonce = nonce()
            val devicePubText = encode(devicePub)
            val deviceNonceText = encode(deviceNonce)
            val transcript = listOf(
                "hatch-link-pairing-v5", "version=5", "initiator_role=mobile", "responder_role=link",
                "device_id=$deviceId", "node_id=$nodeId", "mac=$mac", "model=hatch_link",
                "firmware_version=$version", "selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1",
                "pairing_auth=none", "pairing_auth_epoch=0", "pairing_policy=confirm_app",
                "confirm_timeout_seconds=0", "mobile_pub=$mobilePubText", "device_pub=$devicePubText",
                "mobile_nonce=$mobileNonceText", "device_nonce=$deviceNonceText"
            ).joinToString("\n")
            val hash = sha256(transcript.toByteArray(UTF_8))
            val salt = sha256(mobileNonce + deviceNonce + hash)
            val root = hkdf(secret, salt, "hatch-link ble setup v1".toByteArray(), 32)
            rx = expand(root, "mobile->device".toByteArray(), 32)
            tx = expand(root, "device->mobile".toByteArray(), 32)
            session = encode(sha256("hatch-link session id v1".toByteArray() + hash + secret).copyOf(16))
            deadline = now() + 60_000
            return JSONObject().put("type", "pairing_ready").put("version", 5)
                .put("device_id", deviceId).put("node_id", nodeId).put("mac", mac)
                .put("model", "hatch_link").put("firmware_version", version)
                .put("pairing_auth", "none").put("pairing_auth_epoch", 0)
                .put("pairing_policy", "confirm_app").put("device_pub", devicePubText)
                .put("device_nonce", deviceNonceText).put("transcript_hash", encode(hash)).put("session_id", session)
        } catch (e: PairingException) { reset(); throw e }
        catch (e: Exception) { reset(); throw PairingException() }
    }

    fun decrypt(envelope: JSONObject): JSONObject {
        try {
            if (expired() || envelope.getString("session_id") != session) throw PairingException()
            val counterText = envelope.getString("counter")
            if (!counterText.matches(Regex("[0-9]+")) || counterText.toLong() != rxCounter) throw PairingException()
            val ciphertext = decode(envelope.getString("ciphertext")); val tag = decode(envelope.getString("tag"))
            if (tag.size != 16) throw PairingException()
            val plain = crypt(Cipher.DECRYPT_MODE, rx!!, 0, rxCounter, ciphertext + tag)
            rxCounter++
            return JSONObject(String(plain, UTF_8))
        } catch (e: Exception) { reset(); throw PairingException() }
    }

    fun confirm(command: JSONObject): Boolean {
        val valid = !expired() && rxCounter == 1L && command.length() == 1 &&
            command.optString("action") == "pairing_client_finished"
        if (!valid) reset() else { consent = true; deadline = now() + 120_000 }
        return valid
    }

    fun encrypt(value: JSONObject): JSONObject? {
        if (expired()) return null
        val counter = txCounter++
        val sealed = crypt(Cipher.ENCRYPT_MODE, tx!!, 1, counter, value.toString().toByteArray(UTF_8))
        return JSONObject().put("type", "pairing_encrypted").put("session_id", session)
            .put("counter", counter.toString()).put("ciphertext", encode(sealed.copyOfRange(0, sealed.size - 16)))
            .put("tag", encode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    fun extend() { if (!expired()) deadline = now() + 120_000 }
    private fun expired() = rx == null || now() > deadline
    private fun crypt(mode: Int, key: ByteArray, direction: Int, counter: Long, data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(direction.toByte()).put(byteArrayOf(0, 0, 0)).putLong(counter).array()
        val arrow = if (direction == 0) "m2d" else "d2m"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("hatch-link ble setup v1|$session|$arrow|$counter".toByteArray(UTF_8))
        return cipher.doFinal(data)
    }

    companion object {
        fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        fun decode(value: String): ByteArray {
            if (value.isEmpty() || value.length > 16384 || !value.matches(Regex("[A-Za-z0-9_-]+")) || value.length % 4 == 1) throw PairingException()
            return Base64.getUrlDecoder().decode(value)
        }
        private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) = expand(hmac(salt, ikm), info, length)
        private fun expand(key: ByteArray, info: ByteArray, length: Int): ByteArray {
            var output = ByteArray(0); var previous = ByteArray(0); var i = 1
            while (output.size < length) { previous = hmac(key, previous + info + i.toByte()); output += previous; i++ }
            return output.copyOf(length)
        }
        private fun unsigned32(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray(); return when { bytes.size == 32 -> bytes; bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size); else -> ByteArray(32 - bytes.size) + bytes }
        }
    }
}
