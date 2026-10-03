package dev.cameronpak.muser1.pairing

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingCryptoTest {
    // Public SDK community_v5 fixture, changed only to the published confirm_app policy.
    // Expected transcript hash and record were derived independently using Node crypto HKDF/AES-GCM.
    private val mobile = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
    private val device = "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E"
    private var clock = 0L
    private fun subject(): PairingCrypto {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val params = (generator.generateKeyPair().public as ECPublicKey).params
        val bytes = PairingCrypto.decode(device)
        val factory = KeyFactory.getInstance("EC")
        val key = KeyPair(factory.generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)),
            BigInteger(1, bytes.copyOfRange(33, 65))), params)), factory.generatePrivate(ECPrivateKeySpec(BigInteger.TWO, params)))
        return PairingCrypto("hatch-link:02:00:00:00:00:01", "homelink-000001", "02:00:00:00:00:01",
            now = { clock }, keyPair = { key }, nonce = { (16..31).map(Int::toByte).toByteArray() })
    }
    private fun hello() = JSONObject().put("version", 5).put("pairing_auth", "none").put("pairing_policy", "confirm_app")
        .put("mobile_pub", mobile).put("mobile_nonce", "AAECAwQFBgcICQoLDA0ODw")
    private fun finished() = JSONObject().put("action", "pairing_encrypted").put("session_id", "z0eNLGw5mczvD4a1F2ubSQ")
        .put("counter", "0").put("ciphertext", "uwNX3KEkix5T6nWce3s2SKPPh-5E31x-UIHFSK6MpMUg562B").put("tag", "zT4kU6XK-rM7rvt3VEITLg")

    @Test fun confirmAppMatchesIndependentGoldenAndDirectionKeys() {
        val crypto = subject()
        val ready = crypto.hello(hello())
        assertEquals(device, ready.getString("device_pub"))
        assertEquals("18Rs196tzddPGuj18pZ8rWObWYpwoDUIwocjnUASj_0", ready.getString("transcript_hash"))
        assertEquals("z0eNLGw5mczvD4a1F2ubSQ", ready.getString("session_id"))
        assertTrue(crypto.confirm(crypto.decrypt(finished())))
        val sealed = crypto.encrypt(JSONObject().put("status", "pairing_confirmed"))!!
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = "9abb2954b05e2f5f7112e9bfd41fd970602e27ed07453c1cff51f575de5d0b4e".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, byteArrayOf(1, 0, 0, 0) + ByteArray(8)))
        cipher.updateAAD("hatch-link ble setup v1|z0eNLGw5mczvD4a1F2ubSQ|d2m|0".toByteArray())
        assertEquals("pairing_confirmed", JSONObject(String(cipher.doFinal(PairingCrypto.decode(sealed.getString("ciphertext")) +
            PairingCrypto.decode(sealed.getString("tag"))))).getString("status"))
        val rx = "ee25e7f1eb3c05cc8465634e5f634deed858862b1c4c35fa434dc926f7facaeb".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rx, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(1).array()))
        cipher.updateAAD("hatch-link ble setup v1|z0eNLGw5mczvD4a1F2ubSQ|m2d|1".toByteArray())
        val next = cipher.doFinal("{\"action\":\"wifi_scan\"}".toByteArray())
        val envelope = finished().put("counter", "1").put("ciphertext", PairingCrypto.encode(next.copyOf(next.size - 16)))
            .put("tag", PairingCrypto.encode(next.takeLast(16).toByteArray()))
        assertEquals("wifi_scan", crypto.decrypt(envelope).getString("action"))
    }
    @Test fun replayAndTamperDestroySession() {
        for (tamper in listOf(false, true)) {
            val crypto = subject(); crypto.hello(hello())
            assertTrue(crypto.confirm(crypto.decrypt(finished())))
            val bad = finished()
            if (tamper) bad.put("counter", "1")
            assertThrows(PairingException::class.java) { crypto.decrypt(bad) }
            assertFalse(crypto.confirmed)
            assertNull(crypto.encrypt(JSONObject()))
        }
    }
    @Test fun deadlineAndFirstRecordAreEnforced() {
        val crypto = subject(); crypto.hello(hello())
        assertFalse(crypto.confirm(JSONObject().put("action", "pairing_client_finished")))
        crypto.hello(hello()); clock = 60_001
        assertThrows(PairingException::class.java) { crypto.decrypt(finished()) }
        clock = 0; crypto.hello(hello()); assertTrue(crypto.confirm(crypto.decrypt(finished())))
        clock = 120_001; assertFalse(crypto.confirmed)
        assertNull(crypto.encrypt(JSONObject()))
        assertThrows(PairingException::class.java) { crypto.hello(hello().put("version", 5.1)) }
    }
}
