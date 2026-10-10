package io.github.sparkcyf.voicesipagent.sip.media

/** ITU-T G.711 mu-law / A-law with precomputed decode tables. */
object G711 {
    private val ulawToPcm = ShortArray(256) { decodeUlaw(it).toShort() }
    private val alawToPcm = ShortArray(256) { decodeAlaw(it).toShort() }

    fun ulawDecode(b: Byte): Short = ulawToPcm[b.toInt() and 0xff]
    fun alawDecode(b: Byte): Short = alawToPcm[b.toInt() and 0xff]

    fun ulawEncode(pcm: Short): Byte {
        val bias = 0x84
        var s = pcm.toInt()
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += bias
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0f
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    fun alawEncode(pcm: Short): Byte {
        var s = pcm.toInt()
        val sign = if (s >= 0) 0x80 else { s = -s - 1; 0 }
        if (s > 32767) s = 32767
        val out = if (s < 256) {
            s shr 4
        } else {
            var exp = 7
            var mask = 0x4000
            while ((s and mask) == 0) { exp--; mask = mask shr 1 }
            (exp shl 4) or ((s shr (exp + 3)) and 0x0f)
        }
        return ((sign or out) xor 0x55).toByte()
    }

    private fun decodeUlaw(v: Int): Int {
        val u = v.inv() and 0xff
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0f
        val mag = (((mant shl 3) + 0x84) shl exp) - 0x84
        return if (sign != 0) -mag else mag
    }

    private fun decodeAlaw(v: Int): Int {
        val a = v xor 0x55
        val sign = a and 0x80
        val exp = (a shr 4) and 0x07
        val mant = a and 0x0f
        val mag = if (exp == 0) (mant shl 4) + 8 else ((mant shl 4) + 0x108) shl (exp - 1)
        return if (sign != 0) mag else -mag
    }
}

enum class AudioCodec(val payloadType: Int, val encodingName: String) {
    PCMU(0, "PCMU"),
    PCMA(8, "PCMA");

    fun encode(pcm: ShortArray, out: ByteArray, offset: Int = 0) {
        when (this) {
            PCMU -> for (i in pcm.indices) out[offset + i] = G711.ulawEncode(pcm[i])
            PCMA -> for (i in pcm.indices) out[offset + i] = G711.alawEncode(pcm[i])
        }
    }

    fun decode(data: ByteArray, offset: Int, length: Int): ShortArray = when (this) {
        PCMU -> ShortArray(length) { G711.ulawDecode(data[offset + it]) }
        PCMA -> ShortArray(length) { G711.alawDecode(data[offset + it]) }
    }

    companion object {
        fun byName(name: String): AudioCodec? = entries.firstOrNull { it.encodingName.equals(name, ignoreCase = true) }
    }
}
