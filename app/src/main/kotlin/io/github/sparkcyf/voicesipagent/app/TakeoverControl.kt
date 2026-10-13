package io.github.sparkcyf.voicesipagent.app

/** Explicit switch intent, kept separate from startup and observed Android role changes. */
internal class TakeoverControl(private val host: Host) {
    interface Host {
        val enabled: Boolean
        val active: Boolean
        val needsDialer: Boolean
        val holdsDialer: Boolean
        val keepDialer: Boolean
        fun saveEnabled(on: Boolean)
        fun cancelDialerChange()
        fun prepareDialerChange(acquire: Boolean)
        fun showPending(on: Boolean?)
        fun applyEnabled()
    }

    private var pending: Boolean? = null

    fun request(on: Boolean) {
        host.cancelDialerChange()
        host.saveEnabled(on)
        // Capture the expected holder now, so a later user choice wins while calls finish.
        when {
            on && host.needsDialer && !host.holdsDialer -> host.prepareDialerChange(true)
            !on && host.holdsDialer && !host.keepDialer -> host.prepareDialerChange(false)
        }
        if (host.active) {
            pending = on.takeIf { it != host.enabled }
            host.showPending(pending)
        } else {
            pending = null
            host.applyEnabled()
        }
    }

    /** Gateway shutdown precedes role restoration; active calls retain their bridge. */
    fun applyIfIdle() {
        if (pending == null || host.active) return
        pending = null
        host.applyEnabled()
    }
}
