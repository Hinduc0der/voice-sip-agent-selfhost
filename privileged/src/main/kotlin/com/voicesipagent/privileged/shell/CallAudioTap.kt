package com.voicesipagent.privileged.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.voicesipagent.privileged.AudioMethod
import java.io.FileDescriptor

/**
 * Shell-side call audio tap: downlink capture -> socket, socket -> uplink injection.
 * PCM stays in memory; only counters are logged.
 */
internal class CallAudioTap(
    private val ctx: Context,
    private val method: Int,
    /** Runs once when the tap shuts down, from whichever thread closed it. */
    private val onClosed: () -> Unit,
) {
    private lateinit var record: AudioRecord
    private lateinit var track: AudioTrack
    private lateinit var local: FileDescriptor
    @Volatile private var running = false
    private var downThread: Thread? = null
    private var upThread: Thread? = null

    @Volatile var usedMethod = "none"; private set
    @Volatile var downFrames = 0L; private set
    @Volatile var upFrames = 0L; private set
    /** Max sample magnitude seen over the whole tap. */
    @Volatile var downPeak = 0; private set
    @Volatile var upPeak = 0; private set
    /** Frames whose peak exceeds [VOICE_THRESHOLD], i.e. not silence. */
    @Volatile var downVoiced = 0L; private set
    @Volatile var upVoiced = 0L; private set
    private var windowDown = 0
    private var windowUp = 0
    @Volatile var error: String? = null; private set

    /** Opens the audio paths and returns the app's end of the socket pair. */
    fun open(): ParcelFileDescriptor {
        openAudio()
        val app = FileDescriptor()
        local = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, local, app)
        running = true
        record.startRecording()
        // Two frames of silence absorb scheduling jitter between the app and the track.
        track.write(ByteArray(FRAME * 2), 0, FRAME * 2, AudioTrack.WRITE_NON_BLOCKING)
        track.play()
        downThread = Thread(::downLoop, "tap-down").apply { priority = Thread.MAX_PRIORITY; start() }
        upThread = Thread(::upLoop, "tap-up").apply { priority = Thread.MAX_PRIORITY; start() }
        Log.i(TAG, "tap open method=$usedMethod")
        return ParcelFileDescriptor.dup(app).also { Os.close(app) }
    }

    private fun openAudio() {
        val failures = ArrayList<String>()
        if (method == AudioMethod.AUTO || method == AudioMethod.OFFICIAL) {
            // Same objects AudioManager.getCall{Downlink,Uplink}*() would build, but with an explicit
            // shell-attributed Context: the helpers take attribution from the process, which in the
            // Shizuku UserService is the app package and is rejected by AudioFlinger for uid 2000.
            try {
                openRedirected()
                usedMethod = "official"
                return
            } catch (t: Throwable) {
                failures += "official: ${describe(t)}"
                if (method == AudioMethod.OFFICIAL) throw IllegalStateException(failures.joinToString("; "))
            }
        }
        try {
            openLegacy()
            usedMethod = "legacy"
        } catch (t: Throwable) {
            failures += "legacy: ${describe(t)}"
            throw IllegalStateException(failures.joinToString("; "))
        }
    }

    private fun openRedirected() {
        val pstn = runCatching { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) }.getOrDefault(1)
        val int = Int::class.javaPrimitiveType
        // Attributes must match the AudioManager helpers exactly: without them the record falls back to the
        // mic and the track to media playback on the local speaker, and nothing reaches the call.
        val recAttr = AudioAttributes.Builder().also {
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", int)
                .invoke(it, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        }.build()
        val rb = AudioRecord.Builder().setContext(ctx).setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(rb, recAttr)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(rb, pstn)
        record = rb.build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "PSTN record not initialized" }
        try {
            val trackAttr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).also {
                AudioAttributes.Builder::class.java.getMethod("setSystemUsage", int).invoke(it, USAGE_CALL_ASSISTANT)
            }.build()
            val tb = AudioTrack.Builder().setContext(ctx).setAudioAttributes(trackAttr)
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(tb, pstn)
            track = tb.build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "PSTN track not initialized" }
            Log.i(TAG, "redirected: record src=${record.audioSource} dev=${record.routedDevice?.type} " +
                "track usage=${track.audioAttributes.usage}")
        } catch (t: Throwable) {
            record.release()
            throw t
        }
    }

    /** AirSIM-style fallback for devices without the official interception API. */
    private fun openLegacy() {
        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord.Builder().setContext(ctx).setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minRec, FRAME * 10)).build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "VOICE_DOWNLINK record not initialized" }
        try {
            val minTrack = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setContext(ctx)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(maxOf(minTrack, FRAME * 10))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val am = ShellEnv.audioManager(ctx)
            val tx = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (tx == null || !track.setPreferredDevice(tx)) {
                track.release()
                error("no TYPE_TELEPHONY sink")
            }
        } catch (t: Throwable) {
            record.release()
            throw t
        }
    }

    private fun downLoop() {
        val buf = ByteArray(FRAME)
        try {
            while (running) {
                val n = record.read(buf, 0, FRAME, AudioRecord.READ_BLOCKING)
                if (n < 0) { error = "record read $n"; break }
                if (n < FRAME) continue
                val p = peak(buf)
                if (p > downPeak) downPeak = p
                if (p > VOICE_THRESHOLD) downVoiced++
                if (p > windowDown) windowDown = p
                Os.write(local, buf, 0, FRAME)
                if (++downFrames % 250 == 0L) {
                    // Every 5 s: levels only, never audio content.
                    Log.i(TAG, "levels t=${downFrames / 50}s downPeak5s=$windowDown upPeak5s=$windowUp up=$upFrames")
                    windowDown = 0
                    windowUp = 0
                }
            }
        } catch (e: ErrnoException) {
            if (running && e.errno != OsConstants.EPIPE && e.errno != OsConstants.ECONNRESET) error = "down: ${e.message}"
        } catch (t: Throwable) {
            if (running) error = "down: $t"
        } finally {
            close()
        }
    }

    private fun upLoop() {
        val buf = ByteArray(FRAME)
        try {
            while (running) {
                val n = Os.read(local, buf, 0, FRAME)
                if (n <= 0) break // peer closed
                val p = peak(buf)
                if (p > upPeak) upPeak = p
                if (p > VOICE_THRESHOLD) upVoiced++
                if (p > windowUp) windowUp = p
                var off = 0
                while (off < n && running) {
                    val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { error = "track write $w"; running = false; break }
                    off += w
                }
                upFrames++
            }
        } catch (e: ErrnoException) {
            if (running && e.errno != OsConstants.ECONNRESET) error = "up: ${e.message}"
        } catch (t: Throwable) {
            if (running) error = "up: $t"
        } finally {
            close()
        }
    }

    @Synchronized
    fun close() {
        if (!running && downThread == null) return
        running = false
        try { Os.shutdown(local, OsConstants.SHUT_RDWR) } catch (_: Throwable) {}
        try { record.stop() } catch (_: Throwable) {}
        try { track.pause(); track.flush(); track.stop() } catch (_: Throwable) {}
        try { record.release() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
        try { Os.close(local) } catch (_: Throwable) {}
        downThread = null
        upThread = null
        Log.i(TAG, "tap closed ${stats()}")
        runCatching(onClosed).onFailure { Log.w(TAG, "onClosed failed: $it") }
    }

    fun stats() = "method=$usedMethod down=$downFrames up=$upFrames downPeak=$downPeak upPeak=$upPeak " +
        "downVoiced=$downVoiced upVoiced=$upVoiced" +
        (error?.let { " error=$it" } ?: "")

    private fun peak(b: ByteArray): Int {
        var p = 0
        var i = 0
        while (i + 1 < b.size) {
            val s = kotlin.math.abs(((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt())
            if (s > p) p = s
            i += 2
        }
        return p
    }

    private fun format(mask: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(RATE).setChannelMask(mask).build()

    private fun describe(t: Throwable): String {
        var c = t
        while (c is java.lang.reflect.InvocationTargetException && c.cause != null) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    companion object {
        private const val TAG = "VsaTap"
        const val RATE = 8000
        const val FRAME = 320
        private const val VOICE_THRESHOLD = 300
        /** Hidden AudioAttributes.USAGE_CALL_ASSISTANT, as used by getCallUplinkInjectionAudioTrack. */
        private const val USAGE_CALL_ASSISTANT = 17
    }
}
