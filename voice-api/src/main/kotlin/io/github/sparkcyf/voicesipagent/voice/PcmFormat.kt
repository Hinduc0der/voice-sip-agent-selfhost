package io.github.sparkcyf.voicesipagent.voice

/** Mono PCM16LE in 20 ms frames, fixed for the lifetime of a media port. */
data class PcmFormat(val sampleRate: Int) {
    init {
        require(sampleRate == 8000 || sampleRate == 16000) { "Unsupported PCM rate: $sampleRate" }
    }

    val frameSamples: Int get() = sampleRate * VoiceFormat.FRAME_MS / 1000
    val frameBytes: Int get() = frameSamples * 2

    fun silence() = ShortArray(frameSamples)

    fun decodeLe(bytes: ByteArray, offset: Int = 0, out: ShortArray = silence()): ShortArray {
        require(offset >= 0 && bytes.size - offset >= frameBytes && out.size == frameSamples)
        for (i in out.indices) {
            val lo = bytes[offset + 2 * i].toInt() and 0xff
            val hi = bytes[offset + 2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    fun encodeLe(frame: ShortArray, out: ByteArray = ByteArray(frameBytes), offset: Int = 0): ByteArray {
        require(frame.size == frameSamples && offset >= 0 && out.size - offset >= frameBytes)
        for (i in frame.indices) {
            val s = frame[i].toInt()
            out[offset + 2 * i] = s.toByte()
            out[offset + 2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    companion object {
        val NARROWBAND = PcmFormat(8000)
        val WIDEBAND = PcmFormat(16000)
    }
}
