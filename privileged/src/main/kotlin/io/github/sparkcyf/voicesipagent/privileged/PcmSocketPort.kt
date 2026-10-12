package io.github.sparkcyf.voicesipagent.privileged

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-side [MediaPort] over the SEQPACKET socket returned by openCallAudio(). It is a clock source:
 * every downlink frame from the modem ticks the listener, which keeps the bridge in the modem's
 * clock domain.
 */
class PcmSocketPort(
    private val pfd: ParcelFileDescriptor,
    override val format: PcmFormat,
    private val onClosed: (String) -> Unit,
) : MediaPort, Closeable {
    private val fd = pfd.fileDescriptor
    private val queue = ArrayBlockingQueue<ShortArray>(8)
    @Volatile private var listener: FrameClockListener? = null
    private val closed = AtomicBoolean()
    private val writeBuf = ByteArray(format.frameBytes)

    @Volatile var framesIn = 0L; private set
    @Volatile var framesOut = 0L; private set

    private val reader = Thread(::readLoop, "pcm-socket-rx").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
    }

    fun start() = reader.start()

    override val isClockSource: Boolean get() = true

    override fun setClockListener(listener: FrameClockListener?) {
        this.listener = listener
    }

    override fun readFrame(): ShortArray = queue.poll() ?: format.silence()

    override fun writeFrame(frame: ShortArray) {
        if (closed.get()) return
        synchronized(writeBuf) {
            if (closed.get()) return
            format.encodeLe(frame, writeBuf)
            try {
                check(Os.write(fd, writeBuf, 0, writeBuf.size) == writeBuf.size) { "short PCM packet write" }
                framesOut++
            } catch (e: Exception) {
                closeWith("write: ${e.message}")
            }
        }
    }

    private fun readLoop() {
        // One extra byte detects oversized packets instead of silently truncating them.
        val buf = ByteArray(format.frameBytes + 1)
        var reason = "eof"
        try {
            while (!closed.get()) {
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) break
                check(n == format.frameBytes) { "PCM packet size $n, expected ${format.frameBytes}" }
                val frame = format.decodeLe(buf)
                if (!queue.offer(frame)) { queue.poll(); queue.offer(frame) }
                framesIn++
                listener?.onFrame()
            }
        } catch (e: Exception) {
            reason = "read: ${e.message}"
        }
        closeWith(reason)
    }

    private fun closeWith(reason: String) {
        if (release()) onClosed(reason)
    }

    override fun close() {
        release()
    }

    private fun release(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        listener = null
        try { Os.shutdown(fd, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
        return true
    }
}
