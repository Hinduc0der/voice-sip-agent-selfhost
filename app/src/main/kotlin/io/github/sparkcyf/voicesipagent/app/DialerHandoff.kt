package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.BUSY
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.CHANGED
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.FAILED
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.SUCCESS
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState

data class PreviousDialer(val userId: Int, val packageName: String)
internal data class DialerChange(val acquire: Boolean, val userId: Int, val expectedHolder: String, val previousHolder: String)

/** A saved return destination survives crashes; pending acquisition is never replayed on startup. */
internal class DialerHandoff(private val gateway: String, private val store: Store, private val backend: Backend) {
    interface Store {
        fun load(): PreviousDialer?
        fun save(previous: PreviousDialer): Boolean
        fun clear(): Boolean
    }
    interface Backend {
        fun state(): DialerRoleState
        fun acquire(expectedHolder: String): DialerRoleResult
        fun restore(previousHolder: String): DialerRoleResult
    }
    data class Prepared(val change: DialerChange? = null, val result: DialerRoleResult? = null)

    fun prepare(acquire: Boolean): Prepared {
        val state = backend.state()
        if (acquire && state.holder == gateway) return Prepared(result = DialerRoleResult(SUCCESS, "Already the default dialer.", state))
        if (acquire) return prepareAcquisition(PreviousDialer(state.userId, state.holder))
        if (state.holder != gateway) {
            return Prepared(result = finish(false, DialerRoleResult(CHANGED, "Default dialer has already changed; leaving that choice in place.", state)))
        }
        val previous = store.load()?.takeIf { it.userId == state.userId }?.packageName.orEmpty()
        return Prepared(DialerChange(false, state.userId, state.holder, previous))
    }

    /** Capture the user's explicit request before a stopped helper reconnects. */
    fun prepareAcquisition(previous: PreviousDialer): Prepared {
        if (previous.packageName == gateway) return Prepared(result = DialerRoleResult(SUCCESS, "Already the default dialer."))
        if (!store.save(previous)) {
            return Prepared(result = DialerRoleResult(FAILED, "Could not save the previous dialer; no role change was attempted."))
        }
        return Prepared(DialerChange(true, previous.userId, previous.packageName, previous.packageName))
    }

    fun execute(change: DialerChange): DialerRoleResult {
        val state = backend.state()
        if (state.userId != change.userId || state.holder != change.expectedHolder) {
            return finish(change.acquire, DialerRoleResult(CHANGED, "Default dialer changed while waiting; leaving the new choice in place.", state))
        }
        if (state.inCall == true) return DialerRoleResult(BUSY, "Waiting for current calls to end.", state)
        return finish(change.acquire, if (change.acquire) backend.acquire(change.expectedHolder) else backend.restore(change.previousHolder))
    }

    private fun finish(acquire: Boolean, result: DialerRoleResult): DialerRoleResult {
        // Keep the return destination after uncertain/failed commands, including IPC timeouts.
        if (!acquire && (result.success || result.status == CHANGED) &&
            result.state?.holder?.let { it.isNotEmpty() && it != gateway } == true && !store.clear()) {
            return result.copy(message = result.message + " Could not clear the saved return dialer.")
        }
        return result
    }
}
