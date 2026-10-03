package dev.cameronpak.muser1.transport

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import org.junit.Assert.*
import org.junit.Test

class NoiseSessionTest {
    @Test fun cipherMatchesIndependentBigEndianNonceGolden() {
        val cipher = Noise.createCipher("AESGCM")
        cipher.initializeKey(ByteArray(32) { it.toByte() }, 0)
        cipher.setNonce(257)
        val plaintext = "nonce must be big endian".toByteArray()
        val out = ByteArray(plaintext.size + 16)
        val length = cipher.encryptWithAd("Muse test".toByteArray(), plaintext, 0, out, 0, plaintext.size)
        // Node crypto AES-256-GCM, IV 000000000000000000000101. A little-endian nonce cannot pass.
        val golden = "0662ee15af9ad79b7e0eef8984dd6c43a7b88b01bd3184727eb5330f34c7d7932e32e75cca8fa586"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(golden, out.copyOf(length))
        cipher.destroy()
    }

    @Test fun handshakeAndMuxCipherWorkWithResponder() {
        val initiator = NoiseHandshake()
        val responder = HandshakeState("Noise_XX_25519_AESGCM_SHA256", HandshakeState.RESPONDER)
        responder.localKeyPair.generateKeyPair(); responder.setPrologue(byteArrayOf(), 0, 0); responder.start()
        val m1 = initiator.message1()
        assertEquals(32, m1.size)
        responder.readMessage(m1, 0, m1.size, ByteArray(256), 0)
        val buffer = ByteArray(256)
        val size = responder.writeMessage(buffer, 0, byteArrayOf(), 0, 0)
        val (m3, client) = initiator.message3(buffer.copyOf(size))
        responder.readMessage(m3, 0, m3.size, ByteArray(256), 0)
        val split = responder.split()
        val server = NoiseCipherPair(split.sender, split.receiver)
        val payload = WireCodec.responseEnvelope(ServiceFrame(7, ApplicationResponse(200, emptyList(), "Muse π".toByteArray(), true)))
        assertArrayEquals(payload, client.decrypt(server.encrypt(payload).single()))
        val request = WireCodec.requestEnvelope(ServiceFrame(11, ApplicationRequest("GET", "/probe", emptyList(), byteArrayOf(), true)))
        assertArrayEquals(request, server.decrypt(client.encrypt(request).single()))
        client.destroy(); server.destroy(); responder.destroy(); initiator.destroy()
    }
}
