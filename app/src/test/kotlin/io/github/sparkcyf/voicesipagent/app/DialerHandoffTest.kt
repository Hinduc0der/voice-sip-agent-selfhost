package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState
import org.junit.Assert.*
import org.junit.Test

class DialerHandoffTest {
    private class Store : DialerHandoff.Store {
        var previous: PreviousDialer? = null
        var writeSucceeds = true
        override fun load() = previous
        override fun save(previous: PreviousDialer): Boolean {
            if (writeSucceeds) this.previous = previous
            return writeSucceeds
        }
        override fun clear(): Boolean { previous = null; return true }
    }
    private class Backend(private val store: Store) : DialerHandoff.Backend {
        var observed = DialerRoleState(10, "old.phone", "system.phone", true, true, false)
        var mutations = 0
        var loseReply = false
        var restored = ""
        override fun state() = observed
        override fun acquire(expectedHolder: String): DialerRoleResult {
            assertEquals(PreviousDialer(10, expectedHolder), store.previous)
            mutations++
            observed = observed.copy(holder = "gateway")
            if (loseReply) error("helper died after assignment")
            return DialerRoleResult(DialerRoleResult.SUCCESS, "assigned", observed)
        }
        override fun restore(previousHolder: String): DialerRoleResult {
            mutations++
            restored = previousHolder
            observed = observed.copy(holder = previousHolder.ifEmpty { observed.fallback })
            return DialerRoleResult(DialerRoleResult.SUCCESS, "restored", observed)
        }
    }

    @Test fun previousPackageIsPersistedBeforeAssignmentAndNotOverwrittenWhileHoldingRole() {
        val store = Store()
        val backend = Backend(store)
        val handoff = DialerHandoff("gateway", store, backend)
        assertTrue(handoff.execute(handoff.prepare(true).change!!).success)
        assertTrue(handoff.prepare(true).result!!.success)
        assertEquals(PreviousDialer(10, "old.phone"), store.previous)
        assertEquals(1, backend.mutations)
    }

    @Test fun failedPersistencePreventsAcquisition() {
        val store = Store().apply { writeSucceeds = false }
        val backend = Backend(store)
        val prepared = DialerHandoff("gateway", store, backend).prepare(true)
        assertNull(prepared.change)
        assertEquals(DialerRoleResult.FAILED, prepared.result?.status)
        assertEquals(0, backend.mutations)
    }

    @Test fun existingDialersCallsDeferAcquisitionEvenWithoutAnInCallService() {
        val store = Store()
        val backend = Backend(store).apply { observed = observed.copy(inCall = true) }
        val handoff = DialerHandoff("gateway", store, backend)
        val request = handoff.prepare(true).change!!
        assertTrue(handoff.execute(request).deferred)
        assertEquals(0, backend.mutations)
        backend.observed = backend.observed.copy(inCall = false)
        assertTrue(handoff.execute(request).success)
    }

    @Test fun changingDialerInSettingsWhileWaitingCancelsTheOriginalAcquisition() {
        val store = Store()
        val backend = Backend(store).apply { observed = observed.copy(inCall = true) }
        val handoff = DialerHandoff("gateway", store, backend)
        val request = handoff.prepare(true).change!!
        backend.observed = backend.observed.copy(holder = "chosen.phone", inCall = false)
        assertEquals(DialerRoleResult.CHANGED, handoff.execute(request).status)
        assertEquals(0, backend.mutations)
        assertEquals("chosen.phone", backend.observed.holder)
    }

    @Test fun returnDestinationSurvivesHelperDeathAfterAssignmentAndAppRestart() {
        val store = Store()
        val backend = Backend(store).apply { loseReply = true }
        val handoff = DialerHandoff("gateway", store, backend)
        assertTrue(runCatching { handoff.execute(handoff.prepare(true).change!!) }.isFailure)
        assertEquals(PreviousDialer(10, "old.phone"), store.previous)
        val restarted = DialerHandoff("gateway", store, backend)
        assertEquals(1, backend.mutations) // Creating a coordinator never changes the role.
        assertTrue(restarted.execute(restarted.prepare(false).change!!).success)
        assertEquals("old.phone", backend.observed.holder)
        assertNull(store.previous)
    }

    @Test fun restoreDoesNotOverrideAUserWhoAlreadySelectedAnotherDialer() {
        val store = Store().apply { previous = PreviousDialer(10, "old.phone") }
        val backend = Backend(store).apply { observed = observed.copy(holder = "chosen.phone") }
        val prepared = DialerHandoff("gateway", store, backend).prepare(false)
        assertNull(prepared.change)
        assertEquals(DialerRoleResult.CHANGED, prepared.result?.status)
        assertEquals(0, backend.mutations)
        assertNull(store.previous)
    }

    @Test fun backupFromADifferentAndroidUserIsNeverUsedForRestoration() {
        val store = Store().apply { previous = PreviousDialer(0, "user.zero.phone") }
        val backend = Backend(store).apply { observed = observed.copy(holder = "gateway") }
        val handoff = DialerHandoff("gateway", store, backend)
        assertTrue(handoff.execute(handoff.prepare(false).change!!).success)
        assertEquals("", backend.restored)
        assertEquals("system.phone", backend.observed.holder)
    }
}
