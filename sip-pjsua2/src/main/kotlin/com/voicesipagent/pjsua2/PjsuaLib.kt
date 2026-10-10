package com.voicesipagent.pjsua2

import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pj_log_decoration
import org.pjsip.pjsua2.pjsip_transport_type_e
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

    // pjsua thread only
    lateinit var ep: Endpoint
        private set
    private lateinit var logWriter: LogWriter
    private val udpTransports = HashMap<Int, Int>()

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

    /** Shared UDP transport per local port; kept open so endpoint restarts can rebind instantly. */
    fun udpTransport(port: Int): Int = udpTransports.getOrPut(port) {
        val cfg = TransportConfig()
        cfg.port = port.toLong()
        ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, cfg).also { cfg.delete() }
    }

    /** Graceful: pjsua destroys the socket once pending transactions (e.g. the unregister) finish. */
    fun closeUdpTransport(port: Int) {
        val id = udpTransports.remove(port) ?: return
        try {
            ep.transportClose(id)
        } catch (e: Exception) {
            log.warn("transportClose failed: ${e.message}")
        }
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
            // Conference bridge at the tap's native format: 8 kHz mono, 20 ms. No resampling, no EC/VAD.
            clockRate = 8000
            sndClockRate = 8000
            channelCount = 1
            audioFramePtime = 20
            ptime = 20
            noVad = true
            ecTailLen = 0
        }
        ep.libInit(cfg)
        cfg.delete()
        ep.libStart()
        // No sound device at all: the null device's master port clocks the conference bridge.
        ep.audDevManager().setNullDev()
        ep.codecSetPriority("PCMU/8000", 255)
        ep.codecSetPriority("PCMA/8000", 254)
        ep.codecSetPriority("G722/16000", 0)
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
