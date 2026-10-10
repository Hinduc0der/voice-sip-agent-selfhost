package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec

data class SipAccount(
    /** Registrar / proxy host (IP or name). */
    val server: String,
    val port: Int = 5060,
    val username: String,
    val password: String,
    val authUsername: String = username,
    /** SIP domain for AoR and Request-URIs; defaults to [server]. */
    val domain: String = server,
    val displayName: String? = null,
) {
    override fun toString() = "SipAccount($username@$server:$port)"
}

data class SipConfig(
    val account: SipAccount,
    /** Local SIP UDP port; 0 picks an ephemeral one. */
    val localPort: Int = 0,
    val registerExpiresSec: Int = 300,
    val userAgent: String = "voice-sip-agent/0.1",
    val codecs: List<AudioCodec> = listOf(AudioCodec.PCMU, AudioCodec.PCMA),
    val rtpPortRange: IntRange = 10000..20000,
    /** Hang up a connected call after this long without RTP from the peer. */
    val rtpTimeoutMs: Long = 30_000,
    /** Give up on an unanswered outbound INVITE after this long. */
    val ringTimeoutMs: Long = 60_000,
    /** Only accept SIP/RTP from the registrar's address (LAN deployment). */
    val acceptOnlyFromServer: Boolean = true,
)

/** Minimal logging hook so the stack stays free of platform dependencies. */
interface SipLog {
    fun info(msg: String)
    fun warn(msg: String, t: Throwable? = null)

    /** Full SIP message dumps; off unless explicitly enabled (they contain numbers). */
    fun trace(msg: String) {}

    object Stdout : SipLog {
        override fun info(msg: String) = println("[sip] $msg")
        override fun warn(msg: String, t: Throwable?) = println("[sip] WARN $msg${t?.let { ": $it" } ?: ""}")
    }
}
