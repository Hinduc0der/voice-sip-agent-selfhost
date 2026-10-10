package io.github.sparkcyf.voicesipagent.sip.media

import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.util.TreeMap

/**
 * Timestamp-ordered playout buffer. Packets of any size are reassembled into 20 ms frames.
 * Primes to [targetFrames] before playing, drops ahead when the backlog exceeds [maxFrames]
 * (sender clock faster than ours), and re-primes after a sustained underrun.
 */
class JitterBuffer(
    private val targetFrames: Int = 3,
    private val maxFrames: Int = 10,
) {
    private val frame = VoiceFormat.FRAME_SAMPLES.toLong()
    private val packets = TreeMap<Long, ShortArray>()
    private var highestTs = -1L
    private var playTs = -1L
    private var playing = false
    private var emptyReads = 0

    var underruns = 0L
        private set
    var dropped = 0L
        private set

    @Synchronized
    fun reset() {
        packets.clear()
        highestTs = -1
        playTs = -1
        playing = false
        emptyReads = 0
    }

    /** [ts32] is the raw 32-bit RTP timestamp (as unsigned). */
    @Synchronized
    fun put(ts32: Long, samples: ShortArray) {
        val ts = extend(ts32)
        if (playing && ts + samples.size <= playTs) { dropped++; return }
        packets[ts] = samples
        if (ts > highestTs) highestTs = ts
    }

    /** Fills [out] with the next frame; returns false (and silence) when nothing was playable. */
    @Synchronized
    fun read(out: ShortArray): Boolean {
        out.fill(0)
        if (packets.isEmpty()) {
            if (playing && ++emptyReads >= targetFrames * 2) playing = false
            if (playing) { underruns++; playTs += frame }
            return false
        }
        val end = packets.lastKey() + packets.lastEntry().value.size
        if (!playing) {
            if (end - packets.firstKey() < targetFrames * frame) return false
            playing = true
            playTs = packets.firstKey()
        }
        if (end - playTs > maxFrames * frame) {
            playTs = end - targetFrames * frame
            dropped++
        }
        var got = false
        for (i in 0 until VoiceFormat.FRAME_SAMPLES) {
            val p = playTs + i
            val e = packets.floorEntry(p) ?: continue
            val off = (p - e.key).toInt()
            if (off < e.value.size) { out[i] = e.value[off]; got = true }
        }
        playTs += frame
        while (packets.isNotEmpty() && packets.firstKey() + packets.firstEntry().value.size <= playTs) packets.pollFirstEntry()
        if (got) emptyReads = 0 else underruns++
        return got
    }

    @Synchronized
    fun bufferedFrames(): Int {
        if (packets.isEmpty() || !playing) return 0
        return ((packets.lastKey() + packets.lastEntry().value.size - playTs) / frame).toInt()
    }

    private fun extend(ts32: Long): Long {
        if (highestTs < 0) return ts32
        val base = highestTs and 0xffffffffL.inv()
        var cand = base or ts32
        val half = 1L shl 31
        if (cand - highestTs > half) cand -= 1L shl 32 else if (highestTs - cand > half) cand += 1L shl 32
        return cand
    }
}
