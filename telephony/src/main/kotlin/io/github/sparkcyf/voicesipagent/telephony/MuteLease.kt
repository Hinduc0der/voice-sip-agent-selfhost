package io.github.sparkcyf.voicesipagent.telephony

/** Main-thread ownership of a global mute change, tied to the service that accepted it. */
internal class MuteLease<Owner : Any, Service : Any>(
    private val isMuted: (Service) -> Boolean?,
    private val setMuted: (Service, Boolean) -> Unit,
) {
    private var owner: Owner? = null
    private var service: Service? = null
    private var original = false

    fun acquire(who: Owner, via: Service): Boolean {
        if (owner != null) return owner === who && service === via
        val before = isMuted(via) ?: return false
        owner = who
        service = via
        original = before
        // Keep the lease if this throws: the caller will release it during failure cleanup.
        if (!before) setMuted(via, true)
        return true
    }

    fun release(who: Owner) {
        if (owner !== who) return
        val via = service
        val restore = !original
        owner = null
        service = null
        // Unconditionally undo our change. AudioService may not have reported the mute yet.
        if (via != null && restore) setMuted(via, false)
    }
}
