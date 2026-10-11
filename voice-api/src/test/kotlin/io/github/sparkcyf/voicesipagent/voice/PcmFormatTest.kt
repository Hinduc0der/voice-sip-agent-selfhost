package io.github.sparkcyf.voicesipagent.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFormatTest {
    @Test fun widebandPacketPreservesAllSamplesAndByteOrder() {
        val format = PcmFormat.WIDEBAND
        val samples = ShortArray(320) { (it * 211 - 32768).toShort() }
        val packet = format.encodeLe(samples)
        assertEquals(640, packet.size)
        assertEquals(0, packet[0].toInt())
        assertEquals(-128, packet[1].toInt())
        assertArrayEquals(samples, format.decodeLe(packet))
    }

    @Test fun narrowbandRetainsG711FrameSize() {
        assertEquals(160, PcmFormat.NARROWBAND.silence().size)
        assertEquals(320, VoiceFormat.encodeLe(VoiceFormat.silence()).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun narrowbandFrameCannotBeEncodedAsWideband() {
        PcmFormat.WIDEBAND.encodeLe(PcmFormat.NARROWBAND.silence())
    }
}
