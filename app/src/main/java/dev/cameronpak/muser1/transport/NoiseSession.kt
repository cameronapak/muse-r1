package dev.cameronpak.muser1.transport

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState

internal class NoiseHandshake {
    private val state = HandshakeState("Noise_XX_25519_AESGCM_SHA256", HandshakeState.INITIATOR)
    init { state.localKeyPair.generateKeyPair(); state.setPrologue(byteArrayOf(), 0, 0); state.start() }
    fun message1():ByteArray { val out=ByteArray(256);return out.copyOf(state.writeMessage(out,0,byteArrayOf(),0,0)) }
    fun message3(message2:ByteArray):Pair<ByteArray,NoiseCipherPair>{val payload=ByteArray(65535);state.readMessage(message2,0,message2.size,payload,0);val out=ByteArray(256);val n=state.writeMessage(out,0,byteArrayOf(),0,0);val pair=state.split();return out.copyOf(n) to NoiseCipherPair(pair.sender,pair.receiver)}
    fun destroy()=state.destroy()
}

internal class NoiseCipherPair(private val send:CipherState,private val receive:CipherState) {
    private val decoder=NoiseReassembler()
    @Synchronized fun encrypt(payload:ByteArray):List<ByteArray> = WireCodec.noiseFrames(payload).map { plain->ByteArray(plain.size+send.macLength).also{send.encryptWithAd(null,plain,0,it,0,plain.size)} }
    @Synchronized fun decrypt(cipher:ByteArray):ByteArray? {val plain=ByteArray(cipher.size);val n=receive.decryptWithAd(null,cipher,0,plain,0,cipher.size);return decoder.add(plain.copyOf(n))}
    fun destroy(){send.destroy();receive.destroy()}
}
