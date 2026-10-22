package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A single-account SIP user agent over UDP, exposed to the rest of the app as a [CallEndpoint].
 * All protocol state lives on one scheduler thread; public methods post onto it.
 */
class SipUserAgent(
    val config: SipConfig,
    private val log: SipLog = SipLog.Stdout,
    /** Override to supply the address peers should reach us on (e.g. the Wi-Fi interface). */
    private val localAddressProvider: ((InetAddress) -> InetAddress?)? = null,
) : CallEndpoint {
    override val name: String = "sip"

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "sip-ua").apply { isDaemon = true } }
        .apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
    private val random = SecureRandom()
    internal lateinit var transport: SipTransport
    private lateinit var serverAddr: InetSocketAddress
    internal var localIp: String = "0.0.0.0"
        private set
    internal val localPort: Int get() = transport.localPort

    private val clientTxs = HashMap<String, ClientTransaction>()
    private val serverTxs = HashMap<String, ServerTransaction>()
    internal val calls = HashMap<String, SipCall>()

    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var incomingHandler: IncomingCallHandler? = null
    @Volatile override var status: EndpointStatus = EndpointStatus(false, "stopped")
        private set

    // Registration state
    private val regCallId = newCallId()
    private val regTag = newTag()
    private var regCseq = 0L
    private var regRefresh: ScheduledFuture<*>? = null
    private var regAuthAttempts = 0
    private var started = false
    private var stopping = false

    // Registrar auth (server mode). All touched only from the single sip-ua executor thread.
    private val issuedNonces = HashMap<String, Long>()          // nonce -> issuedAt (ms)
    private val authFailTimes = HashMap<String, ArrayDeque<Long>>() // source IP -> recent failure times
    private val authBanUntil = HashMap<String, Long>()          // source IP -> banned until (ms)

    private val account get() = config.account
    // Identity used when we originate (device-in-itself mode has no configured account).
    private val localUser get() = account.username.ifBlank { "gateway" }
    private val localDomain get() = account.domain.ifBlank { localIp }
    internal val aor: String get() = "sip:$localUser@$localDomain"

    // ---- Server (device-in-itself) mode: learned from the single registered softphone ----
    internal val isServerMode get() = config.serverMode
    private var peerAor: String? = null
    @Volatile private var peerContactUri: String? = null
    private var peerExpiry: ScheduledFuture<*>? = null
    /** Full SIP URI (sip:user@ip:port) to reach the registered phone, or null if none. */
    internal fun serverPeerUri(): String? = peerContactUri
    /** Domain to use in locally generated URIs (our LAN IP when no account domain is set). */
    internal val selfDomain: String get() = localDomain

    // ---------------------------------------------------------------- lifecycle

    fun start() = post {
        if (started) return@post
        started = true
        stopping = false
        transport = SipTransport(config.localPort, log) { msg, from -> post { onMessage(msg, from) } }
        transport.start()
        if (config.serverMode) {
            resolveLocal()
            log.info("server mode on $localIp:$localPort; waiting for a phone to register")
            setStatus(false, "waiting for phone to register")
        } else {
            resolve()
            log.info("started on $localIp:$localPort -> ${account.server}:${account.port}")
            register()
        }
        executor.scheduleWithFixedDelay({ checkMedia() }, 5, 5, TimeUnit.SECONDS)
    }

    /** Unregisters, hangs up all calls and releases sockets. Blocks up to [timeoutMs]. */
    fun stop(timeoutMs: Long = 2000) {
        val done = CountDownLatch(1)
        post {
            if (!started) { done.countDown(); return@post }
            stopping = true
            calls.values.toList().forEach { it.hangup(HangupCause.NORMAL) }
            regRefresh?.cancel(false)
            peerExpiry?.cancel(false)
            if (config.serverMode) done.countDown() else sendRegister(0, null) { done.countDown() }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        post {
            if (::transport.isInitialized) transport.close()
            started = false
            setStatus(false, "stopped")
        }
        executor.shutdown()
    }

    /** Call when the network changes: refreshes our address and re-registers. */
    fun refreshRegistration() = post {
        if (!started || stopping) return@post
        if (config.serverMode) { resolveLocal(); return@post }  // the phone re-registers itself
        resolve()
        register()
    }

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
        val call = SipCall(this, LegDirection.OUTBOUND, Party(target), target, newCallId(), newTag())
        post { call.startOutbound(target, caller) }
        return call
    }

    /**
     * Server mode: deliver an incoming SMS to the registered phone as an out-of-dialog SIP MESSAGE
     * (shown as a chat message in the softphone). Returns false if no phone is currently registered.
     */
    fun sendMessage(fromNumber: String?, text: String): Boolean {
        val peer = peerContactUri ?: return false
        post {
            val fromUser = fromNumber ?: "sms"
            val from = NameAddr(fromNumber, "sip:$fromUser@$localDomain").withTag(newTag())
            val to = NameAddr(null, peer)
            val req = newRequest("MESSAGE", peer, from, to, newCallId(), 1)
            req.headers.add("Content-Type", "text/plain;charset=UTF-8")
            req.body = text.toByteArray(Charsets.UTF_8)
            sendClient(req) { /* delivery response ignored; best-effort */ }
            log.info("SMS from ${redactNumber(fromNumber)} relayed to phone")
        }
        return true
    }

    // ---------------------------------------------------------------- registration

    private fun register() {
        regRefresh?.cancel(false)
        regAuthAttempts = 0
        setStatus(false, "registering")
        sendRegister(config.registerExpiresSec, null)
    }

    private fun sendRegister(expires: Int, auth: Pair<String, String>?, onDone: (() -> Unit)? = null) {
        val req = newRequest(
            method = "REGISTER",
            uri = "sip:${account.domain}",
            from = NameAddr(account.displayName, aor).withTag(regTag),
            to = NameAddr(account.displayName, aor),
            callId = regCallId,
            cseq = ++regCseq,
        )
        req.headers.add("Contact", "<${contactUri()}>")
        req.headers.add("Expires", expires.toString())
        auth?.let { req.headers.add(it.first, it.second) }
        sendClient(req) { r ->
            when {
                r == null -> {
                    onDone?.invoke()
                    if (expires > 0) registerFailed("timeout")
                }
                r.isProvisional -> Unit
                (r.code == 401 || r.code == 407) && regAuthAttempts++ < 2 -> {
                    val authHeader = authorizationFor(r, "REGISTER", req.uri)
                    if (authHeader == null) {
                        onDone?.invoke()
                        registerFailed("unsupported challenge")
                    } else {
                        sendRegister(expires, authHeader, onDone)
                    }
                }
                r.isSuccess -> {
                    onDone?.invoke()
                    regAuthAttempts = 0
                    if (expires == 0) return@sendClient
                    val granted = grantedExpires(r) ?: expires
                    setStatus(true, "registered ($granted s)")
                    log.info("registered as ${account.username}, expires=$granted")
                    val refreshIn = maxOf(granted.toLong() * 1000 * 85 / 100, minOf(granted.toLong() * 1000 - 5000, 30_000))
                    regRefresh = schedule(maxOf(refreshIn, 5_000)) { register() }
                }
                else -> {
                    onDone?.invoke()
                    if (expires > 0) registerFailed("${r.code} ${r.reason}")
                }
            }
        }
    }

    private fun grantedExpires(r: SipResponse): Int? {
        val mine = contactUri()
        for (c in r.headers.getAll("Contact")) {
            val na = NameAddr.parse(c) ?: continue
            if (na.uri.equals(mine, ignoreCase = true)) na.params["expires"]?.toIntOrNull()?.let { return it }
        }
        return r.headers["Expires"]?.trim()?.toIntOrNull()
    }

    private fun registerFailed(why: String) {
        log.warn("registration failed: $why; retrying in 30 s")
        setStatus(false, "registration failed: $why")
        regRefresh = schedule(30_000) { register() }
    }

    private fun setStatus(ready: Boolean, detail: String) {
        val s = EndpointStatus(ready, detail)
        if (s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
    }

    internal fun authorizationFor(r: SipResponse, method: String, uri: String): Pair<String, String>? {
        val proxy = r.code == 407
        val header = r.headers[if (proxy) "Proxy-Authenticate" else "WWW-Authenticate"] ?: return null
        val challenge = DigestAuth.parseChallenge(header) ?: return null
        return try {
            (if (proxy) "Proxy-Authorization" else "Authorization") to
                DigestAuth.authorize(challenge, method, uri, account.authUsername, account.password)
        } catch (e: IllegalArgumentException) {
            log.warn("cannot answer challenge", e)
            null
        }
    }

    // ---------------------------------------------------------------- message dispatch

    private fun onMessage(msg: SipMessage, from: InetSocketAddress) {
        if (!config.serverMode && config.acceptOnlyFromServer && from.address != serverAddr.address) {
            log.warn("dropping SIP from untrusted ${from.address.hostAddress}")
            return
        }
        when (msg) {
            is SipResponse -> onResponse(msg)
            is SipRequest -> onRequest(msg, from)
        }
    }

    private fun onResponse(r: SipResponse) {
        val method = r.cseq?.method ?: return
        val tx = clientTxs[txKey(r.topVia?.branch, method)]
        if (tx != null) {
            tx.onResponse(r)
        } else if (method == "INVITE" && r.isSuccess) {
            calls[r.callId]?.onStray2xx(r)
        }
    }

    private fun onRequest(req: SipRequest, from: InetSocketAddress) {
        if (req.callId == null || req.cseq == null || req.topVia == null || req.headers["From"] == null || req.headers["To"] == null) {
            if (req.method != "ACK") transport.send(buildResponse(req, 400), from)
            return
        }
        if (req.method == "ACK") {
            serverTxs[txKey(req.topVia?.branch, "INVITE")]?.onAck()
            calls[req.callId]?.onAck(req)
            return
        }
        if (config.serverMode && req.method == "REGISTER") {
            onRegister(req, from)
            return
        }
        serverTxs[txKey(req.topVia?.branch, req.method)]?.let {
            it.onRetransmission()
            return
        }
        val tx = ServerTransaction(this, req, from)
        serverTxs[tx.key] = tx
        val call = calls[req.callId]
        val toTag = req.to?.tag
        when {
            req.method == "CANCEL" -> {
                val inviteTx = serverTxs[txKey(req.topVia?.branch, "INVITE")]
                if (inviteTx == null || call == null) {
                    tx.respond(buildResponse(req, 481))
                } else {
                    tx.respond(buildResponse(req, 200, toTag = call.localTag))
                    call.onCancel(inviteTx)
                }
            }
            call != null && (toTag != null || req.method == "INVITE") -> call.onInDialogRequest(req, tx)
            toTag != null -> tx.respond(buildResponse(req, 481))
            req.method == "INVITE" -> onNewInvite(req, tx)
            req.method == "OPTIONS" -> tx.respond(buildResponse(req, 200, toTag = newTag()).also { addCapabilities(it) })
            req.method == "NOTIFY" -> tx.respond(buildResponse(req, 200, toTag = newTag()))
            req.method == "BYE" || req.method == "INFO" || req.method == "UPDATE" -> tx.respond(buildResponse(req, 481))
            else -> tx.respond(buildResponse(req, 405).also { addCapabilities(it) })
        }
    }

    private fun onNewInvite(req: SipRequest, tx: ServerTransaction) {
        val handler = incomingHandler
        // X-VSA-Dial: the number to dial when the PBX keeps the Request-URI user as our own AoR
        // (needed behind registrar-based proxies such as Flexisip).
        val target = req.headers["X-VSA-Dial"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: SipUri.parse(req.uri)?.user ?: req.to?.sipUri?.user
        val pai = req.headers["P-Asserted-Identity"]?.let(NameAddr::parse)
        val fromAddr = req.from!!
        val caller = Party(
            number = pai?.sipUri?.user ?: fromAddr.sipUri?.user,
            displayName = pai?.displayName ?: fromAddr.displayName,
        )
        val call = SipCall(this, LegDirection.INBOUND, caller, target, req.callId!!, newTag())
        log.info("incoming INVITE to ${redactNumber(target)} from ${redactNumber(caller.number)}")
        if (handler == null || stopping) {
            tx.respond(buildResponse(req, 480, toTag = call.localTag))
            return
        }
        if (!call.acceptOffer(req, tx)) return
        calls[call.callId] = call
        handler.onIncomingCall(call)
    }

    // Registrar auth tuning: how long an issued nonce is accepted, and the brute-force ban policy.
    private val nonceTtlMs = 300_000L
    private val authMaxFails = 5          // failures within the window before a ban
    private val authFailWindowMs = 60_000L
    private val authBanMs = 600_000L      // 10 minutes

    /**
     * Challenge/verify a REGISTER when a registrar password is set. Returns true to let the REGISTER
     * through, false when it was handled here (401 sent, or silently dropped as banned/over-limit).
     */
    private fun registrarAuthPassed(req: SipRequest, from: InetSocketAddress): Boolean {
        if (config.registrarPassword.isEmpty()) return true
        val ip = from.address?.hostAddress ?: ""
        val now = System.currentTimeMillis()
        authBanUntil[ip]?.let { until ->
            if (now < until) {
                log.warn("REGISTER from banned $ip dropped (${(until - now) / 1000}s left)")
                return false
            }
            authBanUntil.remove(ip); authFailTimes.remove(ip)
        }
        val authHeader = req.headers["Authorization"]
        val cred = authHeader?.let { DigestAuth.parseCredentials(it) }
        val ok = cred != null && DigestAuth.verify(cred, "REGISTER", config.registrarPassword) { nonce ->
            val issuedAt = issuedNonces[nonce]
            issuedAt != null && now - issuedAt <= nonceTtlMs
        }
        if (ok) {
            authFailTimes.remove(ip); authBanUntil.remove(ip)
            return true
        }
        // A credential-bearing REGISTER that failed counts toward the ban; a first, challenge-less
        // REGISTER does not (that is the normal start of the handshake).
        if (authHeader != null && registrarAuthFailed(ip, now)) {
            log.warn("REGISTER brute-force from $ip: banned for ${authBanMs / 1000}s")
            return false
        }
        val nonce = issueNonce(now)
        val resp = buildResponse(req, 401, toTag = newTag())
        resp.headers.add("WWW-Authenticate", DigestAuth.challengeHeader(config.registrarRealm, nonce))
        transport.send(resp, from)
        log.info("REGISTER challenged (${if (authHeader == null) "no credentials" else "bad credentials"}) from $ip")
        return false
    }

    /** Record a failed auth from [ip]; returns true once the window is over the limit (now banned). */
    private fun registrarAuthFailed(ip: String, now: Long): Boolean {
        val times = authFailTimes.getOrPut(ip) { ArrayDeque() }
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > authFailWindowMs) times.removeFirst()
        if (times.size >= authMaxFails) {
            authBanUntil[ip] = now + authBanMs
            authFailTimes.remove(ip)
            return true
        }
        return false
    }

    /** Issue a fresh nonce, pruning expired ones so the map cannot grow without bound. */
    private fun issueNonce(now: Long): String {
        issuedNonces.entries.removeAll { now - it.value > nonceTtlMs }
        val nonce = DigestAuth.newNonce()
        issuedNonces[nonce] = now
        return nonce
    }

    /** Registrar for device-in-itself mode: remember where the single softphone can be reached. */
    private fun onRegister(req: SipRequest, from: InetSocketAddress) {
        if (!registrarAuthPassed(req, from)) return
        val contactHeader = req.headers["Contact"]
        val aorUser = req.from?.sipUri?.user ?: req.to?.sipUri?.user
        val contactNa = contactHeader?.let { NameAddr.parse(it) }
        val expires = contactNa?.params?.get("expires")?.toIntOrNull()
            ?: req.headers["Expires"]?.trim()?.toIntOrNull() ?: 3600
        val deregister = expires <= 0 || contactHeader == null || contactHeader.contains("*")
        val lease = config.registrarLeaseSec.coerceIn(30, 86_400)
        // Grant a generous lease so the phone refreshes at ~half of it; brief background Doze on the
        // softphone no longer drops the registration. We advertise [lease] but keep the last contact
        // for about twice that (holdMs) before giving up, so a few missed refreshes still ring through.
        val granted = if (deregister) 0 else minOf(if (expires <= 0) lease else expires, lease)
        if (deregister) {
            peerExpiry?.cancel(false)
            peerAor = null; peerContactUri = null
            setStatus(false, "phone unregistered")
            log.info("phone unregistered (${redactNumber(aorUser)})")
        } else {
            val cUser = contactNa?.let { SipUri.parse(it.uri)?.user } ?: aorUser ?: "phone"
            // Reach the phone at the address the REGISTER actually came from (NAT/roaming friendly).
            peerAor = aorUser
            peerContactUri = "sip:$cUser@${from.address.hostAddress}:${from.port}"
            serverAddr = from
            peerExpiry?.cancel(false)
            val holdMs = granted.toLong() * 2 * 1000 + 30_000
            peerExpiry = schedule(holdMs) {
                peerAor = null; peerContactUri = null
                setStatus(false, "phone registration expired")
                log.info("phone registration expired (no refresh for ${holdMs / 1000}s)")
            }
            setStatus(true, "phone registered: ${redactNumber(aorUser)}")
            log.info("phone registered: $aorUser @ ${from.address.hostAddress}:${from.port} lease=${granted}s hold=${holdMs / 1000}s")
        }
        val resp = buildResponse(req, 200, toTag = newTag())
        if (contactHeader != null) resp.headers.add("Contact", "$contactHeader;expires=$granted")
        resp.headers.add("Expires", granted.toString())
        transport.send(resp, from)
    }

    // ---------------------------------------------------------------- helpers used by SipCall

    internal fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                log.warn("UA task failed", t)
            }
        }
    }

    internal fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*> =
        executor.schedule({
            try {
                block()
            } catch (t: Throwable) {
                log.warn("UA timer failed", t)
            }
        }, delayMs, TimeUnit.MILLISECONDS)

    internal fun sendClient(req: SipRequest, handler: (SipResponse?) -> Unit): ClientTransaction {
        val tx = ClientTransaction(this, req, serverAddr, handler)
        clientTxs[tx.key] = tx
        tx.start()
        return tx
    }

    /** Fire-and-forget (ACK for 2xx). */
    internal fun sendRaw(req: SipRequest) = transport.send(req, serverAddr)

    internal fun removeClientTx(tx: ClientTransaction) {
        if (clientTxs[tx.key] === tx) clientTxs.remove(tx.key)
    }

    internal fun removeServerTx(tx: ServerTransaction) {
        if (serverTxs[tx.key] === tx) serverTxs.remove(tx.key)
    }

    internal fun removeCall(call: SipCall) {
        if (calls[call.callId] === call) calls.remove(call.callId)
    }

    internal fun registerCall(call: SipCall) {
        calls[call.callId] = call
    }

    internal fun contactUri(): String = "sip:$localUser@$localIp:$localPort"

    internal fun newRequest(
        method: String,
        uri: String,
        from: NameAddr,
        to: NameAddr,
        callId: String,
        cseq: Long,
        routes: List<String> = emptyList(),
        branch: String = newBranch(),
    ): SipRequest {
        val h = SipHeaders()
        h.add("Via", "SIP/2.0/UDP $localIp:$localPort;branch=$branch;rport")
        routes.forEach { h.add("Route", it) }
        h.add("Max-Forwards", "70")
        h.add("From", from.toString())
        h.add("To", to.toString())
        h.add("Call-ID", callId)
        h.add("CSeq", "$cseq $method")
        h.add("User-Agent", config.userAgent)
        return SipRequest(method, uri, h)
    }

    internal fun buildResponse(req: SipRequest, code: Int, toTag: String? = null): SipResponse {
        val h = SipHeaders()
        req.headers.getAll("Via").forEach { h.add("Via", it) }
        if (req.method == "INVITE" && code in 101..299) h.copyFrom(req.headers, "Record-Route")
        h.add("From", req.headers["From"]!!)
        val to = req.headers["To"]!!
        h.add("To", if (toTag != null && code > 100 && NameAddr.parse(to)?.tag == null) "$to;tag=$toTag" else to)
        h.add("Call-ID", req.callId!!)
        h.add("CSeq", req.headers["CSeq"]!!)
        h.add("Server", config.userAgent)
        return SipResponse(code, SipCodes.reason(code), h)
    }

    internal fun addCapabilities(r: SipMessage) {
        r.headers.set("Allow", ALLOW)
        r.headers.set("Accept", "application/sdp")
    }

    internal fun newRtpSocket(): DatagramSocket = io.github.sparkcyf.voicesipagent.sip.media.RtpSession.bindSocket(null, config.rtpPortRange)

    internal fun newBranch() = "z9hG4bK" + hex(10)
    internal fun newTag() = hex(6)
    private fun newCallId() = hex(12)
    internal fun newSdpSessionId() = (random.nextLong() and Long.MAX_VALUE) % 1_000_000_000L
    internal val logger: SipLog get() = log
    internal val sipConfig: SipConfig get() = config

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun resolve() {
        val server = InetAddress.getByName(account.server)
        serverAddr = InetSocketAddress(server, account.port)
        localIp = (localAddressProvider?.invoke(server) ?: routeTo(server))?.hostAddress ?: "0.0.0.0"
    }

    /** Server mode has no upstream server; just learn our own LAN address. */
    private fun resolveLocal() {
        val probe = runCatching { InetAddress.getByName("8.8.8.8") }.getOrNull()
        localIp = (probe?.let { localAddressProvider?.invoke(it) ?: routeTo(it) })?.hostAddress ?: "0.0.0.0"
    }

    /** The local address the OS would use to reach [dest]. */
    private fun routeTo(dest: InetAddress): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(dest, 9)
            s.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    } catch (e: Exception) {
        null
    }

    private fun checkMedia() {
        val now = System.currentTimeMillis()
        calls.values.toList().forEach { it.checkRtpTimeout(now) }
    }

    companion object {
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, NOTIFY, UPDATE"
    }
}
