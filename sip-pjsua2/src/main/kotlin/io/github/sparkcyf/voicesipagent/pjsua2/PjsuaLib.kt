package io.github.sparkcyf.voicesipagent.pjsua2

import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pj_ssl_sock_proto
import org.pjsip.pjsua2.pj_log_decoration
import org.pjsip.pjsua2.pjsip_transport_type_e
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The process-wide pjsua2 library.
 *
 * pjsua is a per-process singleton that does not like being destroyed and recreated, so it is
 * created once and kept for the life of the process; endpoints only add and remove accounts.
 * Everything that touches pjsua runs on the single "pjsua" thread. That thread also polls the SIP
 * ioqueue (threadCnt = 0) and runs pjsua2's pending jobs (mainThreadOnly), so all SIP callbacks
 * arrive on it too. The only other threads that enter Java are pjmedia's (the conference clock
 * calling AudioMediaPort), and those never call back into pjsua.
 */
internal object PjsuaLib {
    private const val USER_AGENT = "voice-sip-agent/0.2 pjsua2"
    private val DIGITS = Regex("\\d{5,}")

    private val queue = LinkedBlockingQueue<Runnable>()
    private val timers = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "pjsua-timer").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    @Volatile private var thread: Thread? = null
    @Volatile var log: PjsipLog = PjsipLog.Silent

    /** pjsip's own info-level (3) lines; errors and warnings (1-2) always pass. */
    @Volatile var verbose = false

    // pjsua thread only
    lateinit var ep: Endpoint
        private set
    private lateinit var logWriter: LogWriter
    private val transports = HashMap<Pair<SipTransport, Int>, Int>()

    val running: Boolean get() = thread != null

    /** Loads and initialises the library on first use. Throws if that fails. */
    @Synchronized
    fun ensureStarted(log: PjsipLog) {
        this.log = log
        if (thread != null) return
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        val t = Thread({
            try {
                init()
            } catch (e: Throwable) {
                failure = e
                return@Thread
            } finally {
                ready.countDown()
            }
            loop()
        }, "pjsua").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
        }
        t.start()
        ready.await()
        failure?.let { throw IllegalStateException("pjsua2 init failed: ${it.message}", it) }
        thread = t
    }

    fun post(task: () -> Unit) {
        queue.add(Runnable(task))
    }

    fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
        timers.schedule({ post(task) }, delayMs, TimeUnit.MILLISECONDS)

    fun every(periodMs: Long, task: () -> Unit): ScheduledFuture<*> =
        timers.scheduleWithFixedDelay({ post(task) }, periodMs, periodMs, TimeUnit.MILLISECONDS)

    /** The local address may have changed: rebind, re-register and re-INVITE active calls. */
    fun handleIpChange() = post {
        try {
            ep.handleIpChange(IpChangeParam())
        } catch (e: Exception) {
            log.warn("handleIpChange failed", e)
        }
    }

    /** Shared transport per type and local port; kept open so endpoint restarts can rebind instantly. */
    fun transport(type: SipTransport, port: Int): Int = transports.getOrPut(type to port) {
        val cfg = TransportConfig()
        cfg.port = port.toLong()
        val pjType = when (type) {
            SipTransport.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
            SipTransport.TCP -> pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
            SipTransport.TLS -> {
                cfg.tlsConfig.apply {
                    caBuf = systemCaPem()
                    verifyServer = true
                    proto = (pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_2 or pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_3).toLong()
                }
                pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
            }
        }
        ep.transportCreate(pjType, cfg).also { cfg.delete() }
    }

    /** Graceful: pjsua destroys the socket once pending transactions (e.g. the unregister) finish. */
    fun closeTransport(type: SipTransport, port: Int) {
        val id = transports.remove(type to port) ?: return
        try {
            ep.transportClose(id)
        } catch (e: Exception) {
            log.warn("transportClose failed: ${e.message}")
        }
    }

    /** The Android system trust store as one PEM bundle for OpenSSL. */
    private fun systemCaPem(): String {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
        val sb = StringBuilder()
        var n = 0
        for (alias in ks.aliases()) {
            if (!alias.startsWith("system:")) continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            sb.append("-----BEGIN CERTIFICATE-----\n").append(enc.encodeToString(cert.encoded)).append("\n-----END CERTIFICATE-----\n")
            n++
        }
        log.info("TLS trust store: $n system CAs")
        return sb.toString()
    }

    fun redact(s: String): String = DIGITS.replace(s) { "**" + it.value.takeLast(2) }

    private fun init() {
        System.loadLibrary("c++_shared")
        System.loadLibrary("pjsua2")
        ep = Endpoint()
        ep.libCreate()
        val cfg = EpConfig()
        cfg.uaConfig.apply {
            threadCnt = 0
            mainThreadOnly = true
            maxCalls = 4
            userAgent = USER_AGENT
        }
        logWriter = object : LogWriter() {
            override fun write(entry: LogEntry) {
                // msgLogging is off, so no SIP message dumps; digit runs (numbers) are still masked.
                if (entry.level > 2 && !verbose) return
                val msg = "pj: " + redact(entry.msg.trimEnd())
                if (entry.level <= 2) log.warn(msg) else log.info(msg)
            }
        }
        cfg.logConfig.apply {
            writer = logWriter
            level = 3
            consoleLevel = 3 // gates the writer callback too, not just stdout
            msgLogging = 0
            decor = pj_log_decoration.PJ_LOG_HAS_SENDER.toLong()
        }
        cfg.medConfig.apply {
            // Match the 16 kHz mono tap/bridge, 20 ms. PJMEDIA resamples for G.711.
            // EC belongs at the remote microphone endpoint. Permit per-codec VAD below;
            // noVad=true would override the Opus setting when PJSUA creates a stream.
            clockRate = 16000
            sndClockRate = 16000
            channelCount = 1
            audioFramePtime = 20
            ptime = 20
            noVad = false
            ecTailLen = 0
        }
        ep.libInit(cfg)
        cfg.delete()
        // Opus maps VAD to its built-in DTX. G.711 must continue sending audio frames.
        for ((id, vad) in listOf("opus/48000/2" to true, "PCMU/8000" to false, "PCMA/8000" to false)) {
            val param = ep.codecGetParam(id)
            val setting = param.setting
            try {
                setting.vad = vad
                ep.codecSetParam(id, param)
            } finally {
                setting.delete()
                param.delete()
            }
        }
        val opus = ep.codecOpusConfig
        try {
            opus.sample_rate = 16000
            opus.channel_cnt = 1
            opus.frm_ptime = 20
            opus.frm_ptime_denum = 1
            opus.bit_rate = 20000
            opus.complexity = 5
            opus.cbr = false
            // Regenerates Opus fmtp from its codec parameters, including usedtx=1.
            ep.codecOpusConfig = opus
        } finally {
            opus.delete()
        }
        ep.libStart()
        // No sound device at all: the null device's master port clocks the conference bridge.
        ep.audDevManager().setNullDev()
        // RFC 7587 requires opus/48000/2 in SDP even for mono 16 kHz audio. PJMEDIA's
        // Opus config supplies maxplaybackrate/sprop-maxcapturerate=16000 in fmtp.
        ep.codecSetPriority("opus/48000/2", 255)
        ep.codecSetPriority("PCMU/8000", 254)
        ep.codecSetPriority("PCMA/8000", 253)
        log.info("pjsua2 ${ep.libVersion().full} started")
    }

    private fun loop() {
        while (true) {
            while (true) {
                val task = queue.poll() ?: break
                try {
                    task.run()
                } catch (t: Throwable) {
                    log.warn("pjsua task failed", t)
                }
            }
            ep.libHandleEvents(10)
        }
    }
}
