package com.voicesipagent.pjsua2

data class PjsipConfig(
    /** Registrar / proxy host (IP or name). */
    val server: String,
    val port: Int = 5060,
    val username: String,
    val password: String,
    val authUsername: String = username,
    /** SIP domain for the AoR and Request-URIs; defaults to [server]. */
    val domain: String = server,
    val displayName: String? = null,
    /** Local SIP UDP port. */
    val localPort: Int = 5062,
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
    override fun toString() = "PjsipConfig($username@$server:$port local=$localPort)"
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
