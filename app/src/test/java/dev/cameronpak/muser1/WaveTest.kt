package dev.cameronpak.muser1

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveTest {
    @Test fun riffLengthsRateAndPayloadAreAccurate() {
        val pcm = byteArrayOf(1, 2, -3, 4, 5, -6)
        val wav = Wave.encode(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals(42, header.getInt(4))
        assertEquals("WAVEfmt ", String(wav.copyOfRange(8, 16)))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(6, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsHalfASample() { Wave.encode(byteArrayOf(1)) }
}
