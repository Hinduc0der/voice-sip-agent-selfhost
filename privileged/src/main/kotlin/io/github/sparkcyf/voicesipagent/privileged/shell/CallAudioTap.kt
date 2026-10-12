package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.io.FileDescriptor

/**
 * Shell-side call audio tap: downlink capture -> socket, socket -> uplink injection.
 * PCM stays in memory; only counters are logged.
 */
internal class CallAudioTap(
    private val ctx: Context,
    private val method: Int,
    sampleRate: Int,
    /** Runs once when the tap shuts down, from whichever thread closed it. */
    private val onClosed: () -> Unit,
) {
    private val pcm = PcmFormat(sampleRate)
    private val rate = pcm.sampleRate
    private val frameBytes = pcm.frameBytes
    private lateinit var record: AudioRecord
    private lateinit var track: AudioTrack
    private lateinit var local: FileDescriptor
    @Volatile private var running = false
    private var closed = false
    private var downThread: Thread? = null
    private var upThread: Thread? = null
    private var modeThread: Thread? = null
    private val audioManager by lazy { ShellEnv.audioManager(ctx) }
    private var backendFailures = ""

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
        val app = FileDescriptor()
        var result: ParcelFileDescriptor? = null
        try {
            openAudio()
            local = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, local, app)
            val pfd = ParcelFileDescriptor.dup(app)
            result = pfd
            Os.close(app)
            running = true
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "record did not start at $rate Hz" }
            // Two frames of silence absorb scheduling jitter between the app and the track.
            check(track.write(ByteArray(frameBytes * 2), 0, frameBytes * 2, AudioTrack.WRITE_NON_BLOCKING) >= 0) {
                "track prefill failed at $rate Hz"
            }
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "track did not start at $rate Hz" }
            downThread = Thread(::downLoop, "tap-down").apply { priority = Thread.MAX_PRIORITY; start() }
            upThread = Thread(::upLoop, "tap-up").apply { priority = Thread.MAX_PRIORITY; start() }
            modeThread = Thread({
                while (running) {
                    SystemClock.sleep(200)
                    if (running && !isCellularMode(audioManager.mode)) {
                        error = "cellular audio mode lost: ${audioManager.mode}"
                        close()
                    }
                }
            }, "tap-mode").apply { isDaemon = true; start() }
            Log.i(TAG, "tap open method=$usedMethod rate=$rate frameBytes=$frameBytes")
            return pfd
        } catch (t: Throwable) {
            error = "open at $rate Hz: ${describe(t)}"
            runCatching { result?.close() }
            runCatching { Os.close(app) }
            close()
            throw t
        }
    }

    private fun openAudio() {
        require(method in AudioMethod.AUTO..AudioMethod.LEGACY) { "unknown audio method $method" }
        check(isCellularMode(audioManager.mode)) { "not in a cellular call: audio mode=${audioManager.mode}" }
        val capabilities = AudioCapabilities.inspect(ctx)
        val failures = ArrayList<String>()
        if (method == AudioMethod.AUTO || method == AudioMethod.OFFICIAL) {
            // Use the framework helpers when process attribution matches the helper identity;
            // otherwise construct equivalent objects with the explicit attributed context.
            try {
                check(capabilities.redirect) { capabilities.detail }
                openRedirected()
                return
            } catch (t: Throwable) {
                failures += "official: ${describe(t)}"
                releaseAudio()
                if (method == AudioMethod.OFFICIAL) throw IllegalStateException(failures.joinToString("; "))
            }
        }
        try {
            check(capabilities.direct) { capabilities.detail }
            openLegacy()
            usedMethod = "direct"
            backendFailures = failures.joinToString("; ")
        } catch (t: Throwable) {
            failures += "legacy: ${describe(t)}"
            throw IllegalStateException(failures.joinToString("; "))
        }
    }

    // AudioCapabilities checks the helper UID's grants before either backend opens. Lint only
    // sees the APK permissions, which are deliberately not used for privileged audio.
    @SuppressLint("MissingPermission")
    private fun openRedirected() {
        check(ShellEnv.isPstnCallAudioInterceptable() != false) { "PSTN audio interception unavailable" }
        // Helpers are usable only when their process-default attribution is valid. If Shizuku's
        // bootstrap uses the gateway package, use equivalent construction with the helper context.
        val helperFailure = runCatching {
            check(ShellEnv.hasMatchingDefaultAudioAttribution(ctx)) {
                "process-default audio attribution does not match helper; using explicit context"
            }
            record = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
            track = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_OUT_MONO)) as AudioTrack
            check(record.state == AudioRecord.STATE_INITIALIZED && track.state == AudioTrack.STATE_INITIALIZED)
        }.exceptionOrNull()
        if (helperFailure == null) {
            usedMethod = "redirection-helper"
            return
        }
        releaseAudio()
        backendFailures = "helper: ${describe(helperFailure)}"
        val pstn = runCatching { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) }.getOrDefault(1)
        val int = Int::class.javaPrimitiveType
        // Attributes must match the AudioManager helpers exactly: without them the record falls back to the
        // mic and the track to media playback on the local speaker, and nothing reaches the call.
        val recAttr = AudioAttributes.Builder().also {
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", int)
                .invoke(it, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        }.build()
        val rb = AudioRecord.Builder().setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
        setContext(rb)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(rb, recAttr)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(rb, pstn)
        record = rb.build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "PSTN record not initialized at $rate Hz" }
            val trackAttr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).also {
                AudioAttributes.Builder::class.java.getMethod("setSystemUsage", int).invoke(it, USAGE_CALL_ASSISTANT)
            }.build()
            val tb = AudioTrack.Builder().setAudioAttributes(trackAttr)
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            setContext(tb)
            AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(tb, pstn)
            track = tb.build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "PSTN track not initialized at $rate Hz" }
            usedMethod = "redirection-manual"
            Log.i(TAG, "redirected: record src=${record.audioSource} dev=${record.routedDevice?.type} " +
                "track usage=${track.audioAttributes.usage}")
        } catch (t: Throwable) {
            record.release()
            if (::track.isInitialized) track.release()
            throw t
        }
    }

    /** AirSIM-style fallback for devices without the official interception API. */
    @SuppressLint("MissingPermission")
    private fun openLegacy() {
        val minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minRec > 0) { "record buffer size $minRec at $rate Hz" }
        record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minRec, frameBytes * 10))
            .also(::setContext).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "VOICE_DOWNLINK record not initialized at $rate Hz" }
            val minTrack = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minTrack > 0) { "track buffer size $minTrack at $rate Hz" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(maxOf(minTrack, frameBytes * 10))
                .setTransferMode(AudioTrack.MODE_STREAM).also(::setContext).build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "telephony track not initialized at $rate Hz" }
            val am = ShellEnv.audioManager(ctx)
            val tx = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (tx == null || !track.setPreferredDevice(tx)) {
                error(if (tx == null) "TYPE_TELEPHONY output unavailable" else "telephony preferred-device request rejected")
            }
        } catch (t: Throwable) {
            record.release()
            if (::track.isInitialized) track.release()
            throw t
        }
    }

    private fun setContext(builder: Any) {
        AudioBuilderContext.apply(builder, ctx, Context::class.java) {
            ShellEnv.prepareAudioAttribution(ctx)
        }
    }

    private fun releaseAudio() {
        if (::record.isInitialized) runCatching { record.release() }
        if (::track.isInitialized) runCatching { track.release() }
    }

    private fun isCellularMode(mode: Int) = mode == AudioManager.MODE_IN_CALL ||
        (Build.VERSION.SDK_INT >= 33 && mode == 5)

    private fun downLoop() {
        val buf = ByteArray(frameBytes)
        try {
            var offset = 0
            while (running) {
                val n = record.read(buf, offset, frameBytes - offset, AudioRecord.READ_BLOCKING)
                if (n <= 0) { error = "record read $n at $rate Hz"; break }
                offset += n
                if (offset < frameBytes) continue
                offset = 0
                val p = peak(buf)
                if (p > downPeak) downPeak = p
                if (p > VOICE_THRESHOLD) downVoiced++
                if (p > windowDown) windowDown = p
                check(Os.write(local, buf, 0, frameBytes) == frameBytes) { "short downlink packet write" }
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
        val buf = ByteArray(frameBytes + 1)
        try {
            while (running) {
                val n = Os.read(local, buf, 0, buf.size)
                if (n <= 0) break // peer closed
                check(n == frameBytes) { "uplink packet size $n, expected $frameBytes" }
                val p = peak(buf, n)
                if (p > upPeak) upPeak = p
                if (p > VOICE_THRESHOLD) upVoiced++
                if (p > windowUp) windowUp = p
                var off = 0
                while (off < n && running) {
                    val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) { error = "track write $w at $rate Hz"; running = false; break }
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
        if (closed) return
        closed = true
        running = false
        try { Os.shutdown(local, OsConstants.SHUT_RDWR) } catch (_: Throwable) {}
        try { record.stop() } catch (_: Throwable) {}
        try { track.pause(); track.flush(); track.stop() } catch (_: Throwable) {}
        try { record.release() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
        try { Os.close(local) } catch (_: Throwable) {}
        downThread = null
        upThread = null
        modeThread = null
        Log.i(TAG, "tap closed ${stats()}")
        runCatching(onClosed).onFailure { Log.w(TAG, "onClosed failed: $it") }
    }

    fun stats() = "method=$usedMethod rate=$rate frameBytes=$frameBytes down=$downFrames up=$upFrames downPeak=$downPeak upPeak=$upPeak " +
        "downVoiced=$downVoiced upVoiced=$upVoiced" +
        " backendFailures=$backendFailures preferred=${if (::track.isInitialized) runCatching { track.preferredDevice?.type }.getOrNull() else null}" +
        " routed=${if (::track.isInitialized) runCatching { track.routedDevice?.type }.getOrNull() else null}" +
        (error?.let { " error=$it" } ?: "")

    private fun peak(b: ByteArray, size: Int = b.size): Int {
        var p = 0
        var i = 0
        while (i + 1 < size) {
            val s = kotlin.math.abs(((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt())
            if (s > p) p = s
            i += 2
        }
        return p
    }

    private fun format(mask: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(mask).build()

    private fun describe(t: Throwable): String {
        var c = t
        while (c is java.lang.reflect.InvocationTargetException && c.cause != null) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    companion object {
        private const val TAG = "VsaTap"
        private const val VOICE_THRESHOLD = 300
        /** Hidden AudioAttributes.USAGE_CALL_ASSISTANT, as used by getCallUplinkInjectionAudioTrack. */
        private const val USAGE_CALL_ASSISTANT = 17
    }
}
