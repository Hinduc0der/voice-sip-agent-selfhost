package com.voicesipagent.telephony

import android.os.Handler
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import com.voicesipagent.voice.AbstractCallLeg
import com.voicesipagent.voice.HangupCause
import com.voicesipagent.voice.LegDirection
import com.voicesipagent.voice.LegState
import com.voicesipagent.voice.Party
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * A Telecom call as a [com.voicesipagent.voice.CallLeg]. Telecom methods run on the main thread.
 * Audio is opened through [CallAudioOpener] after the call goes ACTIVE (the interception API only
 * works in MODE_IN_CALL), and the microphone is muted so only injected audio reaches the far end.
 */
class CellularLeg internal constructor(
    id: String,
    direction: LegDirection,
    remote: Party,
    target: String?,
    private val endpoint: CellularEndpoint,
    private val main: Handler,
) : AbstractCallLeg(id, "cellular", direction, remote, target) {
    @Volatile internal var call: Call? = null
        private set
    @Volatile private var hangupRequested: HangupCause? = null
    private var audio: CallAudioHandle? = null
    private var audioAttempt = 0
    private val dtmfQueue = ArrayDeque<Pair<Char, Int>>()
    private var dtmfPlaying = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = onTelecomState(state)
    }

    internal fun bind(c: Call) {
        call = c
        c.registerCallback(callback, main)
        hangupRequested?.let { disconnect(c) }
        onTelecomState(c.state)
    }

    internal fun onRemoved() {
        call?.unregisterCallback(callback)
        if (state != LegState.ENDED) finish(causeFromTelecom())
    }

    /** Placement failed before Telecom ever reported the call. */
    internal fun failPlacement(cause: HangupCause) = main.post { finish(cause) }

    private fun onTelecomState(s: Int) {
        when (s) {
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_RINGING,
            Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_PULLING_CALL -> transition(LegState.PROCEEDING)
            Call.STATE_ACTIVE -> {
                if (transition(LegState.CONNECTED)) {
                    endpoint.log("cell $id active")
                    endpoint.setMuted(true)
                    openAudio()
                }
            }
            Call.STATE_DISCONNECTED -> finish(causeFromTelecom())
        }
    }

    private fun openAudio() {
        val opener = endpoint.audioOpener
        audioExecutor.execute {
            try {
                val h = opener.open { reason -> main.post { onAudioLost(reason) } }
                main.post {
                    if (state == LegState.CONNECTED) {
                        audio = h
                        endpoint.log("cell $id audio up")
                        publishMedia(h.port)
                    } else {
                        h.close()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    // MODE_IN_CALL can lag the ACTIVE callback slightly; retry briefly.
                    if (state == LegState.CONNECTED && ++audioAttempt < 5) {
                        endpoint.log("cell $id audio open failed (${e.message}); retry $audioAttempt")
                        main.postDelayed(::openAudio, 300L * audioAttempt)
                    } else if (state == LegState.CONNECTED) {
                        endpoint.log("cell $id audio unavailable: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onAudioLost(reason: String) {
        if (state != LegState.CONNECTED) return
        endpoint.log("cell $id audio lost: $reason")
        audio?.close()
        audio = null
        publishMedia(null)
    }

    private fun finish(cause: HangupCause) {
        audio?.close()
        audio = null
        if (transition(LegState.ENDED, cause)) {
            endpoint.log("cell $id ended: $cause")
            endpoint.onLegEnded(this)
        }
    }

    private fun causeFromTelecom(): HangupCause {
        val dc = call?.details?.disconnectCause ?: return hangupRequested ?: HangupCause.NORMAL
        val answered = state == LegState.CONNECTED
        val reason = dc.reason.orEmpty().uppercase()
        return when (dc.code) {
            DisconnectCause.BUSY -> HangupCause.BUSY
            DisconnectCause.REJECTED -> HangupCause.REJECTED
            DisconnectCause.LOCAL -> hangupRequested ?: HangupCause.NORMAL
            DisconnectCause.REMOTE -> if (answered) HangupCause.NORMAL else HangupCause.UNAVAILABLE
            DisconnectCause.MISSED -> HangupCause.NO_ANSWER
            DisconnectCause.CANCELED -> HangupCause.CANCELLED
            DisconnectCause.RESTRICTED -> HangupCause.FORBIDDEN
            DisconnectCause.ERROR -> if ("INVALID_NUMBER" in reason || "UNOBTAINABLE" in reason) HangupCause.NOT_FOUND else HangupCause.NETWORK_ERROR
            DisconnectCause.ANSWERED_ELSEWHERE, DisconnectCause.CALL_PULLED -> HangupCause.NORMAL
            else -> if (answered) HangupCause.NORMAL else HangupCause.UNAVAILABLE
        }
    }

    // ------------------------------------------------------------ CallLeg API

    override fun ring() {
        // The phone is already ringing for inbound cellular calls; nothing to signal.
    }

    override fun answer() {
        main.post {
            val c = call ?: return@post
            if (c.state == Call.STATE_RINGING) c.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
    }

    override fun hangup(cause: HangupCause) {
        main.post {
            if (state == LegState.ENDED) return@post
            hangupRequested = cause
            val c = call
            if (c == null) {
                // Outbound placement still pending: end now, disconnect if Telecom reports it later.
                finish(cause)
            } else {
                disconnect(c)
            }
        }
    }

    private fun disconnect(c: Call) {
        if (c.state == Call.STATE_RINGING && direction == LegDirection.INBOUND) c.reject(false, null) else c.disconnect()
    }

    override fun sendDtmf(digit: Char, durationMs: Int) {
        main.post {
            dtmfQueue.add(digit to durationMs.coerceIn(MIN_DTMF_MS, 1000))
            if (!dtmfPlaying) playNextDtmf()
        }
    }

    private fun playNextDtmf() {
        val c = call
        val next = dtmfQueue.poll()
        if (c == null || next == null || state != LegState.CONNECTED) {
            dtmfPlaying = false
            return
        }
        dtmfPlaying = true
        c.playDtmfTone(next.first)
        main.postDelayed({
            c.stopDtmfTone()
            main.postDelayed(::playNextDtmf, DTMF_GAP_MS)
        }, next.second.toLong())
    }

    companion object {
        private const val MIN_DTMF_MS = 100
        private const val DTMF_GAP_MS = 60L
        private val audioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "cell-audio-open").apply { isDaemon = true } }
    }
}
