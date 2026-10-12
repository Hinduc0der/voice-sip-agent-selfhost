package io.github.sparkcyf.voicesipagent.telephony

/** Main-thread ownership of asynchronous audio opens. Invalidated results close themselves. */
internal class AudioLease(private val onCloseFailure: (Throwable) -> Unit = {}) {
    class Ticket internal constructor()
    @Volatile private var current: Ticket? = null
    var handle: CallAudioHandle? = null
        private set

    fun begin(): Ticket {
        invalidate()
        return Ticket().also { current = it }
    }

    fun isCurrent(ticket: Ticket) = current === ticket

    fun install(ticket: Ticket, opened: CallAudioHandle): Boolean {
        if (!isCurrent(ticket)) { close(opened); return false }
        if (handle === opened) return true
        if (handle != null) { close(opened); return false }
        handle = opened
        return true
    }

    fun invalidate() {
        current = null
        val old = handle
        handle = null
        old?.let(::close)
    }

    private fun close(handle: CallAudioHandle) {
        runCatching { handle.close() }.onFailure { runCatching { onCloseFailure(it) } }
    }
}
