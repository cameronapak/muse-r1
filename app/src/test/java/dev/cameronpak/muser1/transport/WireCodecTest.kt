package dev.cameronpak.muser1.transport

import org.junit.Assert.*
import org.junit.Test

class WireCodecTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun requestMatchesIndependentGolden() {
        val encoded=WireCodec.requestEnvelope(ServiceFrame(1,ApplicationRequest("GET","/x",listOf(Header("a","b")),byteArrayOf(0x7f),true)))
        assertArrayEquals(hex("121a080112160a0347455412022f781a060a016112016222017f2801"),encoded)
    }

    @Test fun responseGoldenDecodes() {
        val frame=WireCodec.decodeResponseEnvelope(hex("0a0d08071a0908c8011a026f6b2001"))
        assertEquals(7,frame.streamId)
        val response=frame.value as ApplicationResponse
        assertEquals(200,response.status)
        assertEquals("ok",response.body.toString(Charsets.UTF_8))
        assertTrue(response.end)
    }

    @Test fun framingMatchesGoldenAndReassemblesOutOfOrder() {
        assertArrayEquals(hex("087b18012203616263"),WireCodec.encodeNoise(NoiseFrame(123,0,1,"abc".toByteArray())))
        val decoder=NoiseReassembler()
        val a=WireCodec.encodeNoise(NoiseFrame(9,0,2,"hello ".toByteArray()))
        val b=WireCodec.encodeNoise(NoiseFrame(9,1,2,"world".toByteArray()))
        assertNull(decoder.add(b))
        assertEquals("hello world",decoder.add(a)!!.toString(Charsets.UTF_8))
    }

    @Test(expected=ProtocolException::class) fun rejectsDuplicateChunk() {
        val decoder=NoiseReassembler();val f=WireCodec.encodeNoise(NoiseFrame(9,0,2,byteArrayOf(1)))
        decoder.add(f);decoder.add(f)
    }

    @Test(expected=ProtocolException::class) fun rejectsTruncatedDelimitedField() {
        WireCodec.decodeNoise(hex("22050102"))
    }

    @Test fun chunkingCapsEachPayload() {
        val frames=WireCodec.noiseFrames(ByteArray(WireCodec.MAX_CHUNK+1),4)
        assertEquals(2,frames.size)
        assertEquals(WireCodec.MAX_CHUNK,WireCodec.decodeNoise(frames[0]).payload.size)
        assertEquals(1,WireCodec.decodeNoise(frames[1]).payload.size)
    }
}
