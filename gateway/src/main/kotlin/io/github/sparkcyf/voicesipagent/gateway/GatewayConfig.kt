package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause

/** What to do with a second inbound call while a session is active. */
enum class BusyPolicy {
    /** Refuse it (cellular: reject; SIP: 486). */
    REJECT,

    /** Leave side-A calls (cellular) alone so the native phone UI handles them; side B still gets BUSY. */
    IGNORE,
}

data class GatewayConfig(
    /** SIP target (extension / ring group) that receives cellular inbound calls. */
    val cellularInboundTarget: String = "",
    /** Give up if the far side has not answered within this time. */
    val answerTimeoutMs: Long = 45_000,
    /** Hang up if a connected session cannot get audio flowing in this time. */
    val mediaSetupTimeoutMs: Long = 8_000,
    val busyPolicy: BusyPolicy = BusyPolicy.REJECT,
    /** Outbound cellular numbers must start with one of these; empty means any. */
    val allowedDialPrefixes: List<String> = emptyList(),
)

/** Vetoes numbers we must never dial (emergency numbers etc.). Returns null if allowed. */
fun interface DialPolicy {
    fun check(number: String): HangupCause?
}

/** Decides where an inbound leg from one endpoint should be sent on the other. Null = refuse. */
fun interface RouteTarget {
    fun targetFor(inbound: CallLeg): String?
}
