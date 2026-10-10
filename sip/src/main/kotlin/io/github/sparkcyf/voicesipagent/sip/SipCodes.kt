package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.HangupCause

object SipCodes {
    fun reason(code: Int): String = when (code) {
        100 -> "Trying"
        180 -> "Ringing"
        183 -> "Session Progress"
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        480 -> "Temporarily Unavailable"
        481 -> "Call/Transaction Does Not Exist"
        482 -> "Loop Detected"
        486 -> "Busy Here"
        487 -> "Request Terminated"
        488 -> "Not Acceptable Here"
        491 -> "Request Pending"
        500 -> "Server Internal Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        603 -> "Decline"
        else -> if (code < 300) "OK" else "Error"
    }

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
