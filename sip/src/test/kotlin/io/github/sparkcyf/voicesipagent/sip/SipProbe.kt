package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Manual live test (not a unit test). Modes:
 *   echo <target> <secs>  - dial target (e.g. *43 echo test), send 1 s tone / 1 s silence, print RX levels
 *   listen <secs>         - wait for an inbound call, answer it and loop its audio back
 */
fun main(args: Array<String>) {
    val secret = File(System.getProperty("sip.secret")).readLines().map { it.trim() }
    val (host, port) = secret[3].split(':').let { it[0] to (it.getOrNull(1)?.toInt() ?: 5060) }
    val account = SipAccount(server = host, port = port, username = secret[1], password = secret[2], displayName = "probe")
    val verbose = System.getenv("SIP_TRACE") == "1"
    val log = object : SipLog by SipLog.Stdout {
        override fun trace(msg: String) { if (verbose) println(msg) }
    }
    val ua = SipUserAgent(SipConfig(account), log)
    val registered = CountDownLatch(1)
    ua.addStatusListener { _, s -> println("status: ${s.detail}"); if (s.ready) registered.countDown() }
    ua.start()
    check(registered.await(10, TimeUnit.SECONDS)) { "registration failed: ${ua.status.detail}" }

    when (args.getOrElse(0) { "echo" }) {
        "echo" -> echo(ua, args.getOrElse(1) { "*43" }, args.getOrElse(2) { "25" }.toInt())
        "listen" -> listen(ua, args.getOrElse(1) { "60" }.toInt())
        else -> println("unknown mode")
    }
    ua.stop()
}

private fun echo(ua: SipUserAgent, target: String, secs: Int) {
    val ended = CountDownLatch(1)
    val leg = ua.dial(target, Party("probe"))
    leg.addListener(object : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) {
            println("leg state: $state ${leg.endCause ?: ""}")
            if (state == LegState.ENDED) ended.countDown()
        }
        override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
            if (media != null) drive(media, secs, toneGenerator()) { leg.hangup(HangupCause.NORMAL) }
        }
    })
    ended.await(secs + 30L, TimeUnit.SECONDS)
    println("stats: ${(leg as SipCall).mediaStats}")
}

private fun listen(ua: SipUserAgent, secs: Int) {
    val ended = CountDownLatch(1)
    println("waiting ${secs}s for an inbound call to ${ua.config.account.username}...")
    ua.setIncomingCallHandler { leg ->
        println("incoming from ${leg.remote} to ${leg.target}")
        leg.addListener(object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                println("leg state: $state ${leg.endCause ?: ""}")
                if (state == LegState.ENDED) ended.countDown()
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
                // Loopback: whatever arrives is sent back 20 ms later.
                if (media != null) drive(media, secs, { media.readFrame() }) { leg.hangup(HangupCause.NORMAL) }
            }
            override fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) = println("DTMF $digit ($durationMs ms)")
        })
        leg.ring()
        Thread.sleep(1000)
        leg.answer()
    }
    ended.await(secs + 30L, TimeUnit.SECONDS)
}

private fun toneGenerator(): () -> ShortArray {
    var n = 0L
    return {
        val f = ShortArray(VoiceFormat.FRAME_SAMPLES)
        for (i in f.indices) {
            val t = n + i
            f[i] = if ((t / 8000) % 2 == 0L) (8000 * sin(2 * PI * 400 * t / 8000)).toInt().toShort() else 0
        }
        n += f.size
        f
    }
}

/** Plays the clock for a clockless port: one write + one read every 20 ms. */
private fun drive(media: MediaPort, secs: Int, source: () -> ShortArray, done: () -> Unit) {
    val exec = Executors.newSingleThreadScheduledExecutor()
    var ticks = 0
    var sumSq = 0.0
    var peak = 0
    exec.scheduleAtFixedRate({
        media.writeFrame(source())
        val rx = media.readFrame()
        for (s in rx) sumSq += s * s.toDouble()
        peak = maxOf(peak, VoiceFormat.peak(rx))
        if (++ticks % 50 == 0) {
            println("t=${ticks / 50}s rx rms=${sqrt(sumSq / (50 * rx.size)).toInt()} peak=$peak")
            sumSq = 0.0
            peak = 0
        }
        if (ticks >= secs * 50) {
            exec.shutdown()
            done()
        }
    }, 0, 20, TimeUnit.MILLISECONDS)
}
