package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import io.github.sparkcyf.voicesipagent.sip.media.G711
import io.github.sparkcyf.voicesipagent.sip.media.JitterBuffer
import io.github.sparkcyf.voicesipagent.sip.media.Sdp
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MediaTest {
    @Test
    fun g711RoundTripIsClose() {
        for (v in listOf(0, 1, -1, 100, -100, 1000, -1000, 12000, -12000, 32767, -32768)) {
            val u = G711.ulawDecode(G711.ulawEncode(v.toShort())).toInt()
            val a = G711.alawDecode(G711.alawEncode(v.toShort())).toInt()
            val tol = maxOf(abs(v) / 16, 16)
            assertTrue("ulaw $v -> $u", abs(u - v) <= tol)
            assertTrue("alaw $v -> $a", abs(a - v) <= tol)
        }
        // Well-known code points
        assertEquals(0xff.toByte(), G711.ulawEncode(0))
        assertEquals(0xd5.toByte(), G711.alawEncode(0))
    }

    @Test
    fun sdpOfferAnswer() {
        val offer = "v=0\r\no=- 1 1 IN IP4 192.168.0.53\r\ns=Asterisk\r\nc=IN IP4 192.168.0.53\r\nt=0 0\r\n" +
            "m=audio 14000 RTP/AVP 107 8 0 101\r\na=rtpmap:107 opus/48000/2\r\na=rtpmap:8 PCMA/8000\r\n" +
            "a=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-16\r\na=sendrecv\r\n"
        val sdp = Sdp.parse(offer)!!
        assertEquals("192.168.0.53", sdp.address)
        assertEquals(14000, sdp.port)
        assertEquals(AudioCodec.PCMA, sdp.firstSupportedCodec())
        assertEquals(101, sdp.telephoneEventPt)
        assertFalse(sdp.isOnHold)

        val built = Sdp.build(1, 0, "10.0.0.2", 12000, listOf(8 to AudioCodec.PCMA), 101)
        val parsed = Sdp.parse(built)!!
        assertEquals(listOf(8, 101), parsed.payloadTypes)
        assertEquals(12000, parsed.port)
    }

    @Test
    fun sdpDetectsHold() {
        val hold = "v=0\r\nc=IN IP4 192.168.0.53\r\nm=audio 14000 RTP/AVP 0\r\na=sendonly\r\n"
        assertTrue(Sdp.parse(hold)!!.isOnHold)
        assertEquals("recvonly", Sdp.answerDirection("sendonly"))
    }

    private fun frame(v: Int) = ShortArray(VoiceFormat.FRAME_SAMPLES) { v.toShort() }

    @Test
    fun jitterBufferReordersAndPrimes() {
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 6)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        jb.put(160, frame(2))
        assertFalse("not primed yet", jb.read(out))
        jb.put(0, frame(1)) // late but before playout start
        jb.put(320, frame(3))
        assertTrue(jb.read(out)); assertEquals(1, out[0].toInt())
        assertTrue(jb.read(out)); assertEquals(2, out[0].toInt())
        assertTrue(jb.read(out)); assertEquals(3, out[0].toInt())
        assertFalse(jb.read(out)) // underrun -> silence
        assertEquals(0, out[0].toInt())
    }

    @Test
    fun jitterBufferReassemblesOddPacketSizes() {
        val jb = JitterBuffer(targetFrames = 1, maxFrames = 6)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        jb.put(1000, ShortArray(80) { 5 })
        jb.put(1080, ShortArray(240) { 6 })
        assertTrue(jb.read(out))
        assertEquals(5, out[0].toInt())
        assertEquals(6, out[159].toInt())
    }

    @Test
    fun jitterBufferHandlesTimestampWrap() {
        val jb = JitterBuffer(targetFrames = 1, maxFrames = 6)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        jb.put(0xffffff60L, frame(7))
        jb.put(0x00000000L, frame(8))
        assertTrue(jb.read(out)); assertEquals(7, out[0].toInt())
        assertTrue(jb.read(out)); assertEquals(8, out[0].toInt())
    }

    @Test
    fun jitterBufferDropsAheadWhenBacklogged() {
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 4)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        for (i in 0 until 10) jb.put(i * 160L, frame(i))
        assertTrue(jb.read(out))
        assertTrue("skipped ahead, got ${out[0]}", out[0] >= 7)
    }
}
