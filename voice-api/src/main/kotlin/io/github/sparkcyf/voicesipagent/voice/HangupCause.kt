package io.github.sparkcyf.voicesipagent.voice

/** Technology-neutral reason a leg ended; each backend maps to/from its own codes. */
enum class HangupCause {
    NORMAL,
    BUSY,
    NO_ANSWER,
    REJECTED,
    UNAVAILABLE,
    NOT_FOUND,
    FORBIDDEN,
    CANCELLED,
    NETWORK_ERROR,
    INTERNAL_ERROR,
}
