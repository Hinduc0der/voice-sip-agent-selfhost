package io.github.sparkcyf.voicesipagent.voice

/**
 * G.711's fixed 8 kHz format and shared frame/level helpers.
 * Other media paths carry their format through [MediaPort.format].
 */
object VoiceFormat {
    const val SAMPLE_RATE = 8000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    fun silence(): ShortArray = PcmFormat.NARROWBAND.silence()

    /** Little-endian PCM16 bytes -> samples. [bytes] must hold [FRAME_BYTES] from [offset]. */
    fun decodeLe(bytes: ByteArray, offset: Int = 0, out: ShortArray = ShortArray(FRAME_SAMPLES)): ShortArray {
        return PcmFormat.NARROWBAND.decodeLe(bytes, offset, out)
    }

    /** Samples -> little-endian PCM16 bytes. */
    fun encodeLe(frame: ShortArray, out: ByteArray = ByteArray(FRAME_BYTES), offset: Int = 0): ByteArray {
        return PcmFormat.NARROWBAND.encodeLe(frame, out, offset)
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
