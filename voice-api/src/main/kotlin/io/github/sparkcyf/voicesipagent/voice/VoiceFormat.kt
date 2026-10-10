package io.github.sparkcyf.voicesipagent.voice

/**
 * Canonical audio format exchanged between legs: 8 kHz mono PCM16, 20 ms frames.
 * Matches the telephony uplink/downlink taps and G.711 natively, so no resampling is needed.
 */
object VoiceFormat {
    const val SAMPLE_RATE = 8000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    fun silence(): ShortArray = ShortArray(FRAME_SAMPLES)

    /** Little-endian PCM16 bytes -> samples. [bytes] must hold [FRAME_BYTES] from [offset]. */
    fun decodeLe(bytes: ByteArray, offset: Int = 0, out: ShortArray = ShortArray(FRAME_SAMPLES)): ShortArray {
        for (i in 0 until FRAME_SAMPLES) {
            val lo = bytes[offset + 2 * i].toInt() and 0xff
            val hi = bytes[offset + 2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /** Samples -> little-endian PCM16 bytes. */
    fun encodeLe(frame: ShortArray, out: ByteArray = ByteArray(FRAME_BYTES), offset: Int = 0): ByteArray {
        for (i in 0 until FRAME_SAMPLES) {
            val s = frame[i].toInt()
            out[offset + 2 * i] = s.toByte()
            out[offset + 2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    fun peak(frame: ShortArray): Int {
        var p = 0
        for (s in frame) {
            val a = kotlin.math.abs(s.toInt())
            if (a > p) p = a
        }
        return p
    }
}
