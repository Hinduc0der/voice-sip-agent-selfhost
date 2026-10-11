package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import org.pjsip.pjsua2.AudioMediaPort
import org.pjsip.pjsua2.MediaFormatAudio
import org.pjsip.pjsua2.MediaFrame
import org.pjsip.pjsua2.pjmedia_frame_type
import java.util.ArrayDeque

/**
 * Bounded frame queue between two clock domains: the cellular tap (modem clock, drives the gateway's
 * MediaBridge) and pjsua's conference bridge (null sound device clock). Both tick at nominally 20 ms;
 * the queue absorbs scheduling jitter, and when one side runs fast the oldest frame is dropped.
 */
internal class FrameFifo(private val maxFrames: Int = 5) {
    private val q = ArrayDeque<ShortArray>()
    /** Overflow before the consumer starts (pre-bridge backlog) is expected and not counted. */
    @Volatile var counting = false
    var dropped = 0L
        private set
    var underruns = 0L
        private set
    var frames = 0L
        private set

    @Synchronized
    fun push(f: ShortArray) {
        q.addLast(f)
        frames++
        while (q.size > maxFrames) {
            q.removeFirst()
            if (counting) dropped++
        }
    }

    /** Discards queued frames without counting them as drops. */
    @Synchronized
    fun clear() = q.clear()

    @Synchronized
    fun pop(): ShortArray? = q.pollFirst() ?: run { if (frames > 0) underruns++; null }

    override fun toString() = "frames=$frames dropped=$dropped underruns=$underruns"
}

/** The gateway-facing side of a pjsip call: clockless, fed and drained by [ConfPort]. */
internal class PjsipMediaPort : MediaPort {
    override val format = PcmFormat.WIDEBAND
    val fromSip = FrameFifo()
    val toSip = FrameFifo()

    @Volatile private var reading = false

    override val isClockSource = false
    override fun setClockListener(listener: FrameClockListener?) {}
    override fun readFrame(): ShortArray {
        // Audio queued between SIP connect and the bridge starting is stale; start from live audio.
        if (!reading) {
            reading = true
            fromSip.clear()
            fromSip.counting = true
        }
        return fromSip.pop() ?: format.silence()
    }
    override fun writeFrame(frame: ShortArray) {
        require(frame.size == format.frameSamples)
        toSip.counting = true
        toSip.push(frame)
    }

    fun stats() = "fromSip[$fromSip] toSip[$toSip]"
}

/**
 * A conference-bridge port that exchanges 16 kHz/20 ms PCM with [media]. Its callbacks run on pjmedia's
 * clock thread and only touch the FIFOs, never pjsua.
 */
internal class ConfPort(private val media: PjsipMediaPort) : AudioMediaPort() {
    private val format = media.format
    private val rx = ByteArray(format.frameBytes)
    private val tx = ByteArray(format.frameBytes)

    /** Must run on the pjsua thread. */
    fun register(name: String) {
        val fmt = MediaFormatAudio()
        fmt.init(PJMEDIA_FORMAT_L16, format.sampleRate.toLong(), 1, VoiceFormat.FRAME_MS * 1000, 16, 0, 0)
        createPort(name, fmt)
        fmt.delete()
    }

    /** Conference wants audio to send towards the SIP peer. */
    override fun onFrameRequested(frame: MediaFrame) {
        val f = media.toSip.pop() ?: format.silence()
        format.encodeLe(f, tx)
        frame.buf.assignBytes(tx)
        frame.size = tx.size.toLong()
        frame.type = pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO
    }

    /** Audio from the SIP peer, already decoded and mixed by the conference bridge. */
    override fun onFrameReceived(frame: MediaFrame) {
        if (frame.type != pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO) return
        if (frame.buf.copyBytes(rx) < format.frameBytes) return
        media.fromSip.push(format.decodeLe(rx))
    }

    companion object {
        /** PJMEDIA_FORMAT_L16 = PJMEDIA_FORMAT_PACK('L','1','6',' '). */
        private const val PJMEDIA_FORMAT_L16 = 0x2036314CL
    }
}
