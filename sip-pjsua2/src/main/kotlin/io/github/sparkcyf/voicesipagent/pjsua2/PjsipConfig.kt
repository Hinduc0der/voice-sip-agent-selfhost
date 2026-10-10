package io.github.sparkcyf.voicesipagent.pjsua2

enum class SipTransport(val id: String, val defaultPort: Int) {
    UDP("udp", 5060),
    TCP("tcp", 5060),

    /** Server certificate and host name are verified against the Android system CA store. */
    TLS("tls", 5061),
    ;

    companion object {
        fun of(id: String?): SipTransport? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

enum class SrtpPolicy(val id: String) {
    /** Plain RTP only. */
    OFF("off"),

    /** Offer SDES-SRTP alongside plain RTP; falls back to RTP if the peer has no SRTP. */
    OPTIONAL("optional"),

    /** SDES-SRTP or no call (488). */
    MANDATORY("mandatory"),
    ;

    companion object {
        fun of(id: String?): SrtpPolicy? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

data class PjsipConfig(
    /** Registrar / outbound proxy host (IP or name). */
    val server: String,
    val port: Int = 5060,
    val transport: SipTransport = SipTransport.UDP,
    val username: String,
    val password: String,
    val authUsername: String = username,
    /** SIP domain for the AoR and Request-URIs; defaults to [server]. */
    val domain: String = server,
    val displayName: String? = null,
    /** Local SIP port (UDP, or the TCP/TLS listener). */
    val localPort: Int = 5062,
    /** SDES-SRTP policy. Only applied over TLS: without it the keys would travel in clear, so SRTP is off. */
    val srtp: SrtpPolicy = SrtpPolicy.OPTIONAL,
    val registerExpiresSec: Int = 300,
    val rtpPortStart: Int = 10000,
    val rtpPortCount: Int = 10000,
    /** Hang up a connected call after this long without RTP from the peer. */
    val rtpTimeoutMs: Long = 30_000,
    /** Give up on an unanswered outbound INVITE after this long. */
    val ringTimeoutMs: Long = 60_000,
    /** Only accept INVITEs from the registrar's address (LAN deployment). */
    val acceptOnlyFromServer: Boolean = true,
) {
    override fun toString() = "PjsipConfig($username@$server:$port/${transport.id} srtp=${srtp.id} local=$localPort)"
}

/** Logging hook; pjsip's own log lines are passed through with long digit runs redacted. */
interface PjsipLog {
    fun info(msg: String)
    fun warn(msg: String, t: Throwable? = null)

    object Silent : PjsipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }
}
