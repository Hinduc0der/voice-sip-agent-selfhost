package io.github.sparkcyf.voicesipagent.app

/**
 * Lightweight SIP transport / SRTP enums, formerly provided by the pjsua2 module.
 * This fork drops the native pjsip stack and uses the pure-Kotlin built-in stack
 * (UDP, G.711) exclusively, so these live in the app module now.
 */
enum class SipTransport(val id: String, val defaultPort: Int) {
    UDP("udp", 5060),
    TCP("tcp", 5060),
    TLS("tls", 5061),
    ;

    companion object {
        fun of(id: String?): SipTransport? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

enum class SrtpPolicy(val id: String) {
    OFF("off"),
    OPTIONAL("optional"),
    MANDATORY("mandatory"),
    ;

    companion object {
        fun of(id: String?): SrtpPolicy? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
