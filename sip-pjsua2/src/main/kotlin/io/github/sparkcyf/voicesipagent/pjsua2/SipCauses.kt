package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.HangupCause

/** SIP status <-> [HangupCause]; same mapping as the built-in stack's SipCodes. */
internal object SipCauses {
    /** Final response used to refuse an unanswered inbound INVITE. */
    fun forCause(cause: HangupCause): Int = when (cause) {
        HangupCause.BUSY -> 486
        HangupCause.NO_ANSWER -> 480
        HangupCause.REJECTED -> 603
        HangupCause.UNAVAILABLE, HangupCause.NORMAL -> 480
        HangupCause.NOT_FOUND -> 404
        HangupCause.FORBIDDEN -> 403
        HangupCause.CANCELLED -> 487
        HangupCause.NETWORK_ERROR -> 503
        HangupCause.INTERNAL_ERROR -> 500
    }

    fun toCause(code: Int): HangupCause = when (code) {
        486, 600 -> HangupCause.BUSY
        408 -> HangupCause.NO_ANSWER
        480 -> HangupCause.UNAVAILABLE
        487 -> HangupCause.CANCELLED
        603 -> HangupCause.REJECTED
        404, 484, 604 -> HangupCause.NOT_FOUND
        401, 403, 407 -> HangupCause.FORBIDDEN
        500 -> HangupCause.INTERNAL_ERROR
        in 500..599 -> HangupCause.NETWORK_ERROR
        else -> HangupCause.UNAVAILABLE
    }
}
