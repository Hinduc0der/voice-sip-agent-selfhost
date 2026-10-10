package io.github.sparkcyf.voicesipagent.sip.media

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

data class RtpParams(
    val remote: InetSocketAddress,
    val codec: AudioCodec,
    val payloadType: Int,
    val telephoneEventPt: Int?,
    val sendEnabled: Boolean,
)

/**
 * One RTP audio stream (G.711 + RFC 4733). Clockless: [writeFrame] calls pace the outgoing
 * stream, and [readFrame] drains the jitter buffer, so whoever drives the bridge owns the clock.
 */
class RtpSession(
    val socket: DatagramSocket,
    params: RtpParams,
    private val onDtmf: (Char, Int) -> Unit,
) : MediaPort {
    @Volatile private var params = params
    private val ssrc = SecureRandom().nextInt()
    private var seq = SecureRandom().nextInt(0xffff)
    private var ts = SecureRandom().nextInt().toLong() and 0xffffffffL
    private val jitter = JitterBuffer()
    private val sendBuf = ByteArray(12 + 1024)
    private val dtmfQueue = ConcurrentLinkedQueue<Pair<Char, Int>>()
    private var dtmfTx: DtmfTx? = null
    private var dtmfGapFrames = 0
    private var firstPacket = true

    @Volatile private var closed = false
    private var remoteSsrc: Int? = null
    private val rxDtmf = DtmfRx()
    private val rxThread = Thread(::receiveLoop, "rtp-rx-${socket.localPort}").apply { isDaemon = true }

    @Volatile var packetsSent = 0L
        private set
    @Volatile var packetsReceived = 0L
        private set
    @Volatile var lastReceiveAtMs = 0L
        private set

    val localPort: Int get() = socket.localPort
    val jitterUnderruns: Long get() = jitter.underruns

    fun start() {
        lastReceiveAtMs = System.currentTimeMillis()
        rxThread.start()
    }

    fun update(newParams: RtpParams) {
        params = newParams
    }

    override val isClockSource: Boolean get() = false
    override fun setClockListener(listener: FrameClockListener?) {}

    override fun readFrame(): ShortArray {
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        jitter.read(out)
        return out
    }

    fun sendDtmf(digit: Char, durationMs: Int) {
        if (DtmfRx.eventCode(digit) != null) dtmfQueue += digit to durationMs.coerceIn(60, 2000)
    }

    @Synchronized
    override fun writeFrame(frame: ShortArray) {
        if (closed) return
        val p = params
        if (dtmfTx == null && dtmfGapFrames == 0 && p.telephoneEventPt != null) {
            dtmfQueue.poll()?.let { (d, ms) -> dtmfTx = DtmfTx(DtmfRx.eventCode(d)!!, ts, ms * 8) }
        }
        val tx = dtmfTx
        if (tx != null && p.telephoneEventPt != null) {
            tx.elapsed += VoiceFormat.FRAME_SAMPLES
            val ending = tx.elapsed >= tx.totalSamples
            val reps = if (ending) 3 else 1
            repeat(reps) {
                writeHeader(p.telephoneEventPt, marker = tx.first, timestamp = tx.startTs)
                sendBuf[12] = tx.event.toByte()
                sendBuf[13] = ((if (ending) 0x80 else 0) or 10).toByte()
                val dur = minOf(tx.elapsed, 0xffff)
                sendBuf[14] = (dur shr 8).toByte()
                sendBuf[15] = dur.toByte()
                send(16, p)
                tx.first = false
            }
            if (ending) { dtmfTx = null; dtmfGapFrames = 3 }
        } else {
            if (dtmfGapFrames > 0) dtmfGapFrames--
            writeHeader(p.payloadType, marker = firstPacket, timestamp = ts)
            firstPacket = false
            p.codec.encode(frame, sendBuf, 12)
            send(12 + frame.size, p)
        }
        ts = (ts + VoiceFormat.FRAME_SAMPLES) and 0xffffffffL
    }

    private fun writeHeader(pt: Int, marker: Boolean, timestamp: Long) {
        sendBuf[0] = 0x80.toByte()
        sendBuf[1] = ((if (marker) 0x80 else 0) or (pt and 0x7f)).toByte()
        seq = (seq + 1) and 0xffff
        sendBuf[2] = (seq shr 8).toByte(); sendBuf[3] = seq.toByte()
        sendBuf[4] = (timestamp shr 24).toByte(); sendBuf[5] = (timestamp shr 16).toByte()
        sendBuf[6] = (timestamp shr 8).toByte(); sendBuf[7] = timestamp.toByte()
        sendBuf[8] = (ssrc shr 24).toByte(); sendBuf[9] = (ssrc shr 16).toByte()
        sendBuf[10] = (ssrc shr 8).toByte(); sendBuf[11] = ssrc.toByte()
    }

    private fun send(len: Int, p: RtpParams) {
        if (!p.sendEnabled) return
        try {
            socket.send(DatagramPacket(sendBuf, len, p.remote))
            packetsSent++
        } catch (_: Exception) {
            // Transient network errors must not kill the clock thread; RTP timeout handles real loss.
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                pkt.setData(buf, 0, buf.size)
                socket.receive(pkt)
            } catch (e: SocketException) {
                if (closed) return else continue
            } catch (_: Exception) {
                continue
            }
            val p = params
            val len = pkt.length
            if (len < 12 || (buf[0].toInt() and 0xc0) != 0x80) continue
            if (pkt.address != p.remote.address && !isSameHostFallback(pkt.address, p.remote.address)) continue
            val cc = buf[0].toInt() and 0x0f
            val hasExt = (buf[0].toInt() and 0x10) != 0
            val hasPad = (buf[0].toInt() and 0x20) != 0
            var off = 12 + cc * 4
            if (hasExt) {
                if (off + 4 > len) continue
                off += 4 + 4 * (((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff))
            }
            var end = len
            if (hasPad) end -= buf[len - 1].toInt() and 0xff
            if (off > end) continue
            val pt = buf[1].toInt() and 0x7f
            val timestamp = ((buf[4].toLong() and 0xff) shl 24) or ((buf[5].toLong() and 0xff) shl 16) or
                ((buf[6].toLong() and 0xff) shl 8) or (buf[7].toLong() and 0xff)
            val rssrc = ((buf[8].toInt() and 0xff) shl 24) or ((buf[9].toInt() and 0xff) shl 16) or
                ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
            if (remoteSsrc != rssrc) {
                if (remoteSsrc != null) jitter.reset()
                remoteSsrc = rssrc
            }
            packetsReceived++
            lastReceiveAtMs = System.currentTimeMillis()
            when {
                pt == p.telephoneEventPt && end - off >= 4 -> rxDtmf.onPacket(timestamp, buf, off).forEach { (d, ms) -> onDtmf(d, ms) }
                pt == p.payloadType || pt == p.codec.payloadType -> jitter.put(timestamp, p.codec.decode(buf, off, end - off))
                else -> AudioCodec.entries.firstOrNull { it.payloadType == pt }?.let { jitter.put(timestamp, it.decode(buf, off, end - off)) }
            }
        }
    }

    private fun isSameHostFallback(a: InetAddress, b: InetAddress) = b.isAnyLocalAddress || a.isLoopbackAddress

    fun close() {
        closed = true
        socket.close()
    }

    private class DtmfTx(val event: Int, val startTs: Long, val totalSamples: Int) {
        var elapsed = 0
        var first = true
    }

    /** RFC 4733 receiver: reports each event once, at its end packet. */
    private class DtmfRx {
        private var currentTs = -1L
        private var reportedTs = -1L
        private var currentEvent = -1
        private var currentDuration = 0

        fun onPacket(ts: Long, b: ByteArray, off: Int): List<Pair<Char, Int>> {
            val event = b[off].toInt() and 0xff
            val endBit = (b[off + 1].toInt() and 0x80) != 0
            val duration = ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
            val out = ArrayList<Pair<Char, Int>>(2)
            if (ts != currentTs) {
                // A new event began before we saw the previous one's end packets: report it now.
                if (currentTs >= 0 && reportedTs != currentTs) digit(currentEvent)?.let { out += it to maxOf(currentDuration / 8, 40) }
                currentTs = ts
                currentEvent = event
            }
            currentDuration = duration
            if (endBit && reportedTs != ts) {
                reportedTs = ts
                digit(event)?.let { out += it to maxOf(duration / 8, 40) }
            }
            return out
        }

        companion object {
            private const val CHARS = "0123456789*#ABCD"
            fun digit(event: Int): Char? = CHARS.getOrNull(event)
            fun eventCode(c: Char): Int? = CHARS.indexOf(c.uppercaseChar()).takeIf { it >= 0 }
        }
    }

    companion object {
        /** Binds an even UDP port in [range] on [bindAddress]. */
        fun bindSocket(bindAddress: InetAddress?, range: IntRange = 10000..20000): DatagramSocket {
            val rnd = SecureRandom()
            repeat(50) {
                val port = (range.first + rnd.nextInt((range.last - range.first) / 2) * 2)
                try {
                    return DatagramSocket(InetSocketAddress(bindAddress, port))
                } catch (_: SocketException) {
                }
            }
            throw SocketException("no free RTP port in $range")
        }
    }
}
