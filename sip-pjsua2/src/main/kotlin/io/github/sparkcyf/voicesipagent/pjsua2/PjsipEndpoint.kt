package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.pjmedia_srtp_use
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single-account SIP endpoint backed by pjsua2, exposed as a [CallEndpoint]. Drop-in replacement
 * for the built-in stack: same trunk semantics (Request-URI user = number to dial, PAI = caller).
 */
class PjsipEndpoint(
    val config: PjsipConfig,
    internal val log: PjsipLog = PjsipLog.Silent,
) : CallEndpoint {
    override val name: String = "sip"

    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var incomingHandler: IncomingCallHandler? = null
    @Volatile override var status: EndpointStatus = EndpointStatus(false, "stopped")
        private set
    private val ids = AtomicInteger(1)

    // pjsua thread only
    internal var account: GwAccount? = null
        private set
    internal val calls = HashMap<Int, PjsipCall>()
    private var serverAddrs: Set<InetAddress> = emptySet()
    private var rtpCheck: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    private var unregistered = false

    private val hostPort = if (config.port == config.transport.defaultPort) config.server else "${config.server}:${config.port}"
    private val transportParam = if (config.transport == SipTransport.UDP) "" else ";transport=${config.transport.id}"
    /** Over TCP/TLS, or when the domain is not the server, every request goes through the server as proxy. */
    private val useProxy = config.transport != SipTransport.UDP || config.domain != config.server
    /** URIs use the domain; when that is the server itself (and no proxy is used), keep its port too. */
    private val uriHost = if (config.domain == config.server && !useProxy) hostPort else config.domain
    internal val aor = "sip:${config.username}@$uriHost"
    internal fun uriFor(user: String) = "sip:$user@$uriHost"

    // ------------------------------------------------------------ lifecycle

    /** Loads pjsua on first use (throws if the native library cannot start), then registers. */
    fun start() {
        PjsuaLib.ensureStarted(log)
        val addrs = runCatching { InetAddress.getAllByName(config.server).toSet() }
            .onFailure { log.warn("cannot resolve ${config.server}: ${it.message}") }
            .getOrDefault(emptySet())
        PjsuaLib.post {
            serverAddrs = addrs
            try {
                createAccount()
                setStatus(EndpointStatus(false, "registering"))
                rtpCheck = PjsuaLib.every(5000) { calls.values.toList().forEach { it.checkRtp(System.currentTimeMillis()) } }
                log.info("pjsua2 endpoint started: $config")
            } catch (e: Exception) {
                log.warn("account setup failed", e)
                setStatus(EndpointStatus(false, "setup failed: ${e.message}"))
            }
        }
    }

    /**
     * Hangs up calls and unregisters. Blocks up to [timeoutMs]. With [releaseTransport] the local SIP
     * port is closed too (after the unregister completes); otherwise it is kept for the next endpoint.
     */
    fun stop(timeoutMs: Long = 2000, releaseTransport: Boolean = true) {
        if (!PjsuaLib.running) return
        val done = CountDownLatch(1)
        PjsuaLib.post {
            try {
            stopped = true
            rtpCheck?.cancel(false)
            calls.values.toList().forEach { it.doHangup(HangupCause.NORMAL) }
            account?.let { acc ->
                // Wait for the unregister to complete: a successor account reuses the same Contact, and
                // a late unregister would remove the binding it just refreshed.
                if (status.ready) {
                    runCatching { acc.setRegistration(false) }
                    val deadline = System.currentTimeMillis() + 1500
                    while (!unregistered && System.currentTimeMillis() < deadline) PjsuaLib.ep.libHandleEvents(20)
                }
                // shutdown() detaches from pjsua; the Java wrapper must be freed explicitly.
                try { acc.shutdown() } finally { acc.delete(); account = null }
            }
            account = null
            if (releaseTransport) PjsuaLib.closeTransport(config.transport, config.localPort)
            setStatus(EndpointStatus(false, "stopped"))
            } finally { done.countDown() }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Network changed: pjsua rebinds, re-registers and re-INVITEs active calls with the new address. */
    fun refreshRegistration() {
        if (PjsuaLib.running) PjsuaLib.handleIpChange()
    }

    private fun createAccount() {
        if (config.transport != SipTransport.TLS && config.srtp != SrtpPolicy.OFF) {
            log.info("SRTP ${config.srtp.id} ignored: needs TLS signalling, using plain RTP")
        }
        val tid = PjsuaLib.transport(config.transport, config.localPort)
        val ac = AccountConfig()
        ac.idUri = aor
        ac.regConfig.apply {
            registrarUri = "sip:$hostPort$transportParam"
            timeoutSec = config.registerExpiresSec.toLong()
            retryIntervalSec = 30
            firstRetryIntervalSec = 5
        }
        ac.sipConfig.apply {
            authCreds.add(AuthCredInfo("digest", "*", config.authUsername, 0, config.password))
            transportId = tid
            if (useProxy) proxies.add("sip:$hostPort$transportParam;lr")
        }
        ac.natConfig.apply {
            sipOutboundUse = 0
        }
        ac.mediaConfig.apply {
            srtpUse = when {
                config.transport != SipTransport.TLS -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                config.srtp == SrtpPolicy.OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                config.srtp == SrtpPolicy.MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
                else -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
            }
            transportConfig.port = config.rtpPortStart.toLong()
            transportConfig.portRange = config.rtpPortCount.toLong()
        }
        val acc = GwAccount()
        acc.create(ac, true)
        ac.delete()
        account = acc
    }

    // ------------------------------------------------------------ CallEndpoint

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        incomingHandler = handler
    }

    override fun addStatusListener(listener: EndpointStatusListener) {
        statusListeners.addIfAbsent(listener)
    }

    override fun removeStatusListener(listener: EndpointStatusListener) {
        statusListeners.remove(listener)
    }

    override fun dial(target: String, caller: Party): CallLeg {
        val leg = PjsipCall(this, LegDirection.OUTBOUND, Party(target), target, "p${ids.getAndIncrement()}")
        if (stopped || !PjsuaLib.running) {
            leg.doHangup(HangupCause.INTERNAL_ERROR)
            return leg
        }
        PjsuaLib.post { leg.startOutbound(target, caller) }
        return leg
    }

    private fun setStatus(s: EndpointStatus) {
        if (s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
    }

    // ------------------------------------------------------------ pjsua callbacks

    private fun onRegState(prm: OnRegStateParam) {
        if (stopped) {
            if (prm.expiration == 0L) unregistered = true
            return
        }
        val code = prm.code
        val ok = code / 100 == 2 && prm.expiration > 0
        val s = if (ok) EndpointStatus(true, "registered (pjsua2, ${prm.expiration}s)")
        else EndpointStatus(false, "register failed: $code ${prm.reason}")
        if (s.ready != status.ready || !s.ready) log.info("registration: $code ${prm.reason}")
        setStatus(s)
    }

    private fun onIncoming(acc: GwAccount, prm: OnIncomingCallParam) {
        val rdata = prm.rdata
        val src = rdata.srcAddress
        val msg = rdata.wholeMsg
        val target = Invite.target(msg)
        val caller = Invite.caller(msg)
        val leg = PjsipCall(this, LegDirection.INBOUND, caller, target, "p${ids.getAndIncrement()}")
        val call = leg.GwCall(acc, prm.callId)
        log.info("incoming INVITE to ${redactNumber(target)} from ${redactNumber(caller.number)}")
        val handler = incomingHandler
        val refuse = when {
            config.acceptOnlyFromServer && !fromServer(src) -> 403
            handler == null || stopped -> 480
            else -> 0
        }
        if (refuse != 0) {
            if (refuse == 403) log.warn("rejecting INVITE from unexpected source")
            val op = CallOpParam()
            op.statusCode = refuse
            runCatching { call.hangup(op) }
            op.delete()
            PjsuaLib.post { call.delete() }
            return
        }
        leg.attachInbound(call)
        handler!!.onIncomingCall(leg)
    }

    private fun fromServer(src: String): Boolean {
        if (serverAddrs.isEmpty()) return true
        val host = src.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return addr in serverAddrs
    }

    companion object {
        /** All endpoints must be stopped before releasing the shared native library. */
        fun stopLibrary() = PjsuaLib.stop()

        /** Passes pjsip's info-level log lines through (call/registration traces). Off by default. */
        fun setVerboseLogging(on: Boolean) {
            PjsuaLib.verbose = on
        }
    }

    internal inner class GwAccount : Account() {
        override fun onRegState(prm: OnRegStateParam) = this@PjsipEndpoint.onRegState(prm)
        override fun onIncomingCall(prm: OnIncomingCallParam) = onIncoming(this, prm)
    }
}

/** Minimal parsing of a received INVITE for the trunk semantics pjsua does not expose directly. */
internal object Invite {
    private val USER = Regex("^[^ ]+ (?:sips?|tel):([^@;> ]+)")

    /** Header carrying the number to dial when the Request-URI user must stay the gateway's own AoR. */
    const val DIAL_HEADER = "X-VSA-Dial"

    /**
     * The number the PBX wants us to dial: [DIAL_HEADER] if present (needed behind registrar-based
     * proxies such as Flexisip, which route on the Request-URI user), else the Request-URI user.
     */
    fun target(msg: String): String? =
        header(msg, DIAL_HEADER, null)?.takeIf { it.isNotBlank() } ?: requestUser(msg)

    fun requestUser(msg: String): String? = USER.find(msg.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)

    fun caller(msg: String): Party {
        val pai = header(msg, "P-Asserted-Identity", null)
        val from = header(msg, "From", "f")
        val src = pai ?: from
        return Party(number = src?.let(::uriUser) ?: from?.let(::uriUser), displayName = src?.let(::display))
    }

    private fun header(msg: String, name: String, compact: String?): String? {
        for (line in msg.lineSequence().drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val n = line.substring(0, colon).trim()
            if (n.equals(name, true) || (compact != null && n.equals(compact, true))) return line.substring(colon + 1).trim()
        }
        return null
    }

    private fun uriUser(v: String): String? = Regex("(?:sips?|tel):([^@;> ]+)").find(v)?.groupValues?.get(1)

    private fun display(v: String): String? {
        val lt = v.indexOf('<')
        if (lt <= 0) return null
        return v.substring(0, lt).trim().trim('"').ifEmpty { null }
    }
}
