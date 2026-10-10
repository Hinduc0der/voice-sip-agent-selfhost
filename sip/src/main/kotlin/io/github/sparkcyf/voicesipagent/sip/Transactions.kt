package io.github.sparkcyf.voicesipagent.sip

import java.net.InetSocketAddress
import java.util.concurrent.ScheduledFuture

internal const val T1 = 500L
internal const val T2 = 4000L
internal const val TX_TIMEOUT = 64 * T1

/** RFC 3261 §17.1 client transaction over UDP (all calls happen on the UA thread). */
internal class ClientTransaction(
    private val ua: SipUserAgent,
    val request: SipRequest,
    private val dest: InetSocketAddress,
    /** Receives provisional and final responses; null means the transaction timed out. */
    private val handler: (SipResponse?) -> Unit,
) {
    val key: String = txKey(request.topVia?.branch, request.method)
    private val isInvite = request.method == "INVITE"
    private var interval = T1
    private var retransmitTask: ScheduledFuture<*>? = null
    private var timeoutTask: ScheduledFuture<*>? = null
    private var ack: SipRequest? = null
    var gotProvisional = false
        private set
    var completed = false
        private set

    fun start() {
        ua.transport.send(request, dest)
        scheduleRetransmit()
        timeoutTask = ua.schedule(TX_TIMEOUT) {
            if (!completed) {
                completed = true
                retransmitTask?.cancel(false)
                ua.removeClientTx(this)
                handler(null)
            }
        }
    }

    private fun scheduleRetransmit() {
        retransmitTask = ua.schedule(interval) {
            if (completed || (isInvite && gotProvisional)) return@schedule
            ua.transport.send(request, dest)
            interval = if (isInvite) interval * 2 else minOf(interval * 2, T2)
            scheduleRetransmit()
        }
    }

    fun onResponse(r: SipResponse) {
        if (completed) {
            // Retransmitted final: re-ACK non-2xx; anything else is absorbed.
            ack?.let { ua.transport.send(it, dest) }
            return
        }
        if (r.isProvisional) {
            gotProvisional = true
            handler(r)
            return
        }
        completed = true
        retransmitTask?.cancel(false)
        timeoutTask?.cancel(false)
        if (isInvite && !r.isSuccess) {
            ack = buildNon2xxAck(r).also { ua.transport.send(it, dest) }
        }
        if (isInvite && r.isSuccess) {
            // 2xx retransmissions belong to the dialog, not to this transaction.
            ua.removeClientTx(this)
        } else {
            ua.schedule(if (isInvite) TX_TIMEOUT else 5000L) { ua.removeClientTx(this) }
        }
        handler(r)
    }

    private fun buildNon2xxAck(r: SipResponse): SipRequest {
        val h = SipHeaders()
        h.add("Via", request.headers.getAll("Via").first())
        request.headers.getAll("Route").forEach { h.add("Route", it) }
        h.add("From", request.headers["From"]!!)
        h.add("To", r.headers["To"] ?: request.headers["To"]!!)
        h.add("Call-ID", request.callId!!)
        h.add("CSeq", "${request.cseq!!.seq} ACK")
        h.add("Max-Forwards", "70")
        return SipRequest("ACK", request.uri, h)
    }
}

/** RFC 3261 §17.2 server transaction; INVITE finals are retransmitted until ACKed. */
internal class ServerTransaction(
    private val ua: SipUserAgent,
    val request: SipRequest,
    val source: InetSocketAddress,
) {
    val key: String = txKey(request.topVia?.branch, request.method)
    private val isInvite = request.method == "INVITE"
    var lastResponse: SipResponse? = null
        private set
    private var retransmitTask: ScheduledFuture<*>? = null
    private var acked = false

    /** Fires if an INVITE final response is never ACKed. */
    var onAckTimeout: (() -> Unit)? = null

    val isFinal: Boolean get() = (lastResponse?.code ?: 0) >= 200

    fun respond(r: SipResponse) {
        if (isFinal) return
        lastResponse = r
        ua.transport.send(r, source)
        if (r.code < 200) return
        if (isInvite) {
            var interval = T1
            fun again() {
                retransmitTask = ua.schedule(interval) {
                    if (acked) return@schedule
                    ua.transport.send(r, source)
                    interval = minOf(interval * 2, T2)
                    again()
                }
            }
            again()
            ua.schedule(TX_TIMEOUT) {
                if (!acked) {
                    retransmitTask?.cancel(false)
                    onAckTimeout?.invoke()
                }
                ua.removeServerTx(this)
            }
        } else {
            ua.schedule(TX_TIMEOUT) { ua.removeServerTx(this) }
        }
    }

    fun onRetransmission() {
        lastResponse?.let { ua.transport.send(it, source) }
    }

    fun onAck() {
        acked = true
        retransmitTask?.cancel(false)
    }
}

internal fun txKey(branch: String?, method: String) = "${branch ?: "nobranch"}|${if (method == "ACK") "INVITE" else method}"
