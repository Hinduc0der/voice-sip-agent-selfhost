package com.voicesipagent.pjsua2

import com.voicesipagent.voice.AbstractCallLeg
import com.voicesipagent.voice.CallLeg
import com.voicesipagent.voice.HangupCause
import com.voicesipagent.voice.LegDirection
import com.voicesipagent.voice.LegState
import com.voicesipagent.voice.Party
import com.voicesipagent.voice.redactNumber
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.CallSendDtmfParam
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnDtmfDigitParam
import org.pjsip.pjsua2.SipHeader
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_dtmf_method
import java.util.concurrent.ScheduledFuture

/**
 * One SIP call as a [CallLeg]. All pjsua objects are created, used and deleted on the pjsua thread;
 * the public leg methods just post there.
 */
internal class PjsipCall(
    private val owner: PjsipEndpoint,
    direction: LegDirection,
    remote: Party,
    target: String?,
    id: String,
) : AbstractCallLeg(id, "sip", direction, remote, target) {
    private val log get() = owner.log

    // pjsua thread only
    private var call: GwCall? = null
    private var conf: ConfPort? = null
    private var peer: AudioMedia? = null
    private val port = PjsipMediaPort()
    private var ringTimer: ScheduledFuture<*>? = null
    private var localCause: HangupCause? = null
    private var lastRxPackets = -1L
    private var lastRxChangeMs = 0L

    val pjId: Int get() = call?.id ?: -1

    // ------------------------------------------------------------ setup

    fun startOutbound(target: String, caller: Party) {
        val acc = owner.account ?: return endNow(HangupCause.UNAVAILABLE, "no account")
        val c = GwCall(acc)
        val prm = CallOpParam(true)
        val display = (caller.displayName ?: caller.number ?: owner.config.displayName)?.replace("\"", "")
        if (display != null) prm.txOption.localUri = "\"$display\" <${owner.aor}>"
        caller.number?.let { n ->
            prm.txOption.headers.add(SipHeader().apply { hName = "P-Asserted-Identity"; hValue = "<${owner.uriFor(n)}>" })
        }
        try {
            c.makeCall(owner.uriFor(target), prm)
        } catch (e: Exception) {
            log.warn("makeCall failed: ${PjsuaLib.redact(e.message.orEmpty())}")
            c.delete()
            return endNow(HangupCause.NETWORK_ERROR, "makeCall failed")
        } finally {
            prm.delete()
        }
        call = c
        owner.calls[c.id] = this
        log.info("dialing ${redactNumber(target)} (call $id)")
        transition(LegState.PROCEEDING)
        ringTimer = PjsuaLib.schedule(owner.config.ringTimeoutMs) {
            if (state < LegState.CONNECTED) {
                log.info("call $id ring timeout")
                doHangup(HangupCause.NO_ANSWER)
            }
        }
    }

    /** Takes over an INVITE that pjsua has already parsed. */
    fun attachInbound(c: GwCall) {
        call = c
        owner.calls[c.id] = this
        transition(LegState.PROCEEDING)
    }

    // ------------------------------------------------------------ CallLeg

    override fun ring() = PjsuaLib.post {
        val c = call ?: return@post
        if (direction != LegDirection.INBOUND || state >= LegState.CONNECTED) return@post
        answerWith(c, 180)
        transition(LegState.RINGING)
    }

    override fun answer() = PjsuaLib.post {
        val c = call ?: return@post
        if (direction != LegDirection.INBOUND || state >= LegState.CONNECTED) return@post
        answerWith(c, 200)
    }

    override fun hangup(cause: HangupCause) = PjsuaLib.post { doHangup(cause) }

    override fun sendDtmf(digit: Char, durationMs: Int) = PjsuaLib.post {
        val c = call ?: return@post
        if (state != LegState.CONNECTED) return@post
        val p = CallSendDtmfParam()
        p.method = pjsua_dtmf_method.PJSUA_DTMF_METHOD_RFC2833
        p.digits = digit.toString()
        p.duration = durationMs.toLong()
        try {
            c.sendDtmf(p)
        } catch (e: Exception) {
            log.warn("sendDtmf failed: ${e.message}")
        } finally {
            p.delete()
        }
    }

    // ------------------------------------------------------------ internals (pjsua thread)

    private fun answerWith(c: GwCall, code: Int) {
        val prm = CallOpParam()
        prm.statusCode = code
        try {
            c.answer(prm)
        } catch (e: Exception) {
            log.warn("answer($code) failed: ${e.message}")
        } finally {
            prm.delete()
        }
    }

    fun doHangup(cause: HangupCause) {
        val c = call
        if (c == null) {
            endNow(cause, null)
            return
        }
        if (localCause == null) localCause = cause
        val prm = CallOpParam()
        // Only used for an unanswered inbound INVITE; otherwise pjsua sends CANCEL or BYE.
        if (direction == LegDirection.INBOUND && state < LegState.CONNECTED) prm.statusCode = SipCauses.forCause(cause)
        try {
            c.hangup(prm)
        } catch (e: Exception) {
            // Already disconnected or never sent: finish locally.
            finish(cause)
        } finally {
            prm.delete()
        }
    }

    private fun endNow(cause: HangupCause, why: String?) {
        if (why != null) log.info("call $id: $why")
        transition(LegState.ENDED, cause)
    }

    internal fun onState(c: GwCall) {
        val info = try { c.info } catch (e: Exception) { return }
        val st = info.state
        val code = info.lastStatusCode
        when (st) {
            pjsip_inv_state.PJSIP_INV_STATE_EARLY ->
                if (direction == LegDirection.OUTBOUND && (code == 180 || code == 183)) transition(LegState.RINGING)
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                ringTimer?.cancel(false)
                lastRxChangeMs = System.currentTimeMillis()
                if (transition(LegState.CONNECTED)) log.info("call $id connected (${codecOf(c)})")
                if (peer != null) publishMedia(port)
            }
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                val cause = localCause ?: if (state == LegState.CONNECTED) HangupCause.NORMAL else SipCauses.toCause(code)
                log.info("call $id ended: $code $cause")
                finish(cause)
            }
        }
        info.delete()
    }

    internal fun onMediaState(c: GwCall) {
        val info = try { c.info } catch (e: Exception) { return }
        val mi = info.media
        for (i in 0 until mi.size) {
            val m = mi[i]
            if (m.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
            if (m.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) continue
            try {
                val am = c.getAudioMedia(m.index.toInt())
                val cp = conf ?: ConfPort(port).also { it.register("gw-$id"); conf = it }
                am.startTransmit(cp)
                cp.startTransmit(am)
                peer?.takeIf { it !== am }?.delete()
                peer = am
                log.info("call $id audio bridged (conf slot ${am.portId} <-> ${cp.portId})")
            } catch (e: Exception) {
                log.warn("call $id media connect failed: ${e.message}")
            }
            break
        }
        info.delete()
        if (peer != null && state == LegState.CONNECTED) publishMedia(port)
    }

    internal fun onDtmf(digit: String) {
        digit.firstOrNull()?.let { publishDtmf(it, CallLeg.DEFAULT_DTMF_MS) }
    }

    /** Dead-peer check for connected calls; called every few seconds on the pjsua thread. */
    internal fun checkRtp(now: Long) {
        val c = call ?: return
        if (state != LegState.CONNECTED || peer == null) return
        val rx = try {
            val ss = c.getStreamStat(0)
            ss.rtcp.rxStat.pkt.also { ss.delete() }
        } catch (_: Exception) {
            return
        }
        if (rx != lastRxPackets) {
            lastRxPackets = rx
            lastRxChangeMs = now
        } else if (now - lastRxChangeMs > owner.config.rtpTimeoutMs) {
            log.info("call $id: no RTP for ${owner.config.rtpTimeoutMs / 1000}s, hanging up")
            doHangup(HangupCause.NETWORK_ERROR)
        }
    }

    private fun finish(cause: HangupCause) {
        ringTimer?.cancel(false)
        val c = call
        call = null
        if (c != null) owner.calls.remove(c.id)
        transition(LegState.ENDED, cause)
        log.info("call $id media ${port.stats()}")
        // Defer deletes: we may be inside a callback of exactly these objects.
        val cp = conf
        val am = peer
        conf = null
        peer = null
        PjsuaLib.post {
            am?.delete()
            cp?.delete()
            c?.delete()
        }
    }

    private fun codecOf(c: GwCall): String = try {
        val si = c.getStreamInfo(0)
        si.codecName.also { si.delete() }
    } catch (_: Exception) {
        "?"
    }

    /** pjsua2 call object; forwards callbacks to the leg. */
    internal inner class GwCall(acc: PjsipEndpoint.GwAccount, callId: Int = -1) : Call(acc, callId) {
        override fun onCallState(prm: OnCallStateParam) = onState(this)
        override fun onCallMediaState(prm: OnCallMediaStateParam) = onMediaState(this)
        override fun onDtmfDigit(prm: OnDtmfDigitParam) = onDtmf(prm.digit)
    }
}
