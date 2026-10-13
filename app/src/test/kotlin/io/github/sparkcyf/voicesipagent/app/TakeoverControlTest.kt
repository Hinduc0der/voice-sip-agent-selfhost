package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState
import org.junit.Assert.*
import org.junit.Test

class TakeoverControlTest {
    private class Fixture : TakeoverControl.Host, DialerHandoff.Store, DialerHandoff.Backend {
        override var enabled = false
        override var active = false
        override var needsDialer = true
        override var keepDialer = false
        override val holdsDialer get() = observed.holder == "gateway"
        var savedEnabled = false
        var observed = DialerRoleState(0, "usual.phone", "system.phone", true, true, false)
        var previous: PreviousDialer? = null
        var pendingChange: DialerChange? = null
        var pendingSwitch: Boolean? = null
        var failPreparation = false
        var failRestoration = false
        var result: DialerRoleResult? = null
        val events = mutableListOf<String>()
        private val handoff = DialerHandoff("gateway", this, this)
        val control = TakeoverControl(this)

        override fun saveEnabled(on: Boolean) { savedEnabled = on }
        override fun cancelDialerChange() { pendingChange = null }
        override fun prepareDialerChange(acquire: Boolean) {
            events += if (acquire) "prepare acquire" else "prepare restore"
            if (failPreparation) {
                result = DialerRoleResult(DialerRoleResult.FAILED, "helper disconnected")
                return
            }
            val prepared = handoff.prepare(acquire)
            pendingChange = prepared.change
            result = prepared.result
        }
        override fun showPending(on: Boolean?) { pendingSwitch = on }
        override fun applyEnabled() {
            assertFalse("Never rebuild an active bridge", active)
            enabled = savedEnabled
            pendingSwitch = null
            events += if (enabled) "gateway on" else "gateway off"
        }
        override fun load() = previous
        override fun save(previous: PreviousDialer): Boolean { this.previous = previous; return true }
        override fun clear(): Boolean { previous = null; return true }
        override fun state() = observed
        override fun acquire(expectedHolder: String): DialerRoleResult {
            assertEquals(PreviousDialer(0, expectedHolder), previous)
            events += "acquire"
            observed = observed.copy(holder = "gateway")
            return DialerRoleResult(DialerRoleResult.SUCCESS, "acquired", observed)
        }
        override fun restore(previousHolder: String): DialerRoleResult {
            assertFalse("Gateway must stop before returning the role", enabled)
            events += "restore"
            if (failRestoration) return DialerRoleResult(DialerRoleResult.FAILED, "restore failed", observed)
            observed = observed.copy(holder = previousHolder)
            return DialerRoleResult(DialerRoleResult.SUCCESS, "restored", observed)
        }
        fun press(on: Boolean) { control.request(on); settle() }
        fun settle() {
            if (active) return
            control.applyIfIdle()
            pendingChange?.let {
                result = handoff.execute(it)
                if (!result!!.deferred) pendingChange = null
            }
        }
    }

    @Test fun defaultDialerSwitchSavesPreviousAndStopsGatewayBeforeRestoring() {
        val f = Fixture()
        f.press(true)
        assertEquals("gateway", f.observed.holder)
        assertEquals(PreviousDialer(0, "usual.phone"), f.previous)
        f.press(false)
        assertEquals("usual.phone", f.observed.holder)
        assertNull(f.previous)
        assertEquals(listOf("prepare acquire", "gateway on", "acquire", "prepare restore", "gateway off", "restore"), f.events)
    }

    @Test fun companionSwitchDoesNotRequestDialerOwnership() {
        val f = Fixture().apply { needsDialer = false }
        f.press(true)
        f.press(false)
        assertEquals(listOf("gateway on", "gateway off"), f.events)
        assertEquals("usual.phone", f.observed.holder)
    }

    @Test fun switchingBackOnCancelsQueuedRestoreWithoutRebuildingActiveBridge() {
        val f = Fixture()
        f.press(true)
        f.active = true
        f.observed = f.observed.copy(inCall = true)
        f.press(false)
        assertEquals(false, f.pendingSwitch)
        assertTrue(f.enabled)
        assertNotNull(f.pendingChange)
        f.press(true)
        assertNull(f.pendingSwitch)
        assertNull(f.pendingChange)
        f.active = false
        f.observed = f.observed.copy(inCall = false)
        f.settle()
        assertEquals("gateway", f.observed.holder)
        assertEquals(1, f.events.count { it == "gateway on" })
        assertFalse(f.events.contains("gateway off"))
        assertFalse(f.events.contains("restore"))
    }

    @Test fun offDuringCallStopsOnlyAfterCleanupThenRestores() {
        val f = Fixture()
        f.press(true)
        f.active = true
        f.press(false)
        assertTrue(f.enabled)
        assertEquals("gateway", f.observed.holder)
        f.active = false
        f.settle()
        assertFalse(f.enabled)
        assertEquals("usual.phone", f.observed.holder)
        assertEquals(listOf("gateway off", "restore"), f.events.takeLast(2))
    }

    @Test fun turningOffCancelsAcquisitionWaitingForTheOtherDialersCall() {
        val f = Fixture().apply { observed = observed.copy(inCall = true) }
        f.press(true)
        assertTrue(f.result!!.deferred)
        f.press(false)
        f.observed = f.observed.copy(inCall = false)
        f.settle()
        assertFalse(f.enabled)
        assertNull(f.pendingChange)
        assertEquals("usual.phone", f.observed.holder)
        assertFalse(f.events.contains("acquire"))
    }

    @Test fun invisibleTelecomCallDelaysRestorationButGatewayStillStops() {
        val f = Fixture()
        f.press(true)
        f.observed = f.observed.copy(inCall = true)
        f.press(false)
        assertFalse(f.enabled)
        assertTrue(f.result!!.deferred)
        assertEquals("gateway", f.observed.holder)
        f.observed = f.observed.copy(inCall = false)
        f.settle()
        assertEquals("usual.phone", f.observed.holder)
        assertEquals(listOf("gateway off", "restore"), f.events.takeLast(2))
    }

    @Test fun userSelectedDialerWinsOverDeferredRestoration() {
        val f = Fixture()
        f.press(true)
        f.active = true
        f.press(false)
        f.observed = f.observed.copy(holder = "chosen.phone")
        f.active = false
        f.settle()
        assertFalse(f.enabled)
        assertEquals("chosen.phone", f.observed.holder)
        assertEquals(DialerRoleResult.CHANGED, f.result!!.status)
        assertFalse(f.events.contains("restore"))
    }

    @Test fun failedRestoreLeavesGatewayOffAndReturnDestinationAvailableForRecovery() {
        val f = Fixture()
        f.press(true)
        f.failRestoration = true
        f.press(false)
        assertFalse(f.enabled)
        assertEquals("gateway", f.observed.holder)
        assertEquals(PreviousDialer(0, "usual.phone"), f.previous)
        assertEquals(DialerRoleResult.FAILED, f.result!!.status)
    }

    @Test fun missingHelperDoesNotPreventGatewayShutdown() {
        val f = Fixture()
        f.press(true)
        f.failPreparation = true
        f.press(false)
        assertFalse(f.enabled)
        assertEquals("gateway", f.observed.holder)
        assertEquals(DialerRoleResult.FAILED, f.result!!.status)
    }

    @Test fun keepRoleOptionOnlyAffectsRestoration() {
        val f = Fixture().apply { keepDialer = true }
        f.press(true)
        f.press(false)
        assertFalse(f.enabled)
        assertEquals("gateway", f.observed.holder)
        assertFalse(f.events.contains("prepare restore"))
        assertEquals(PreviousDialer(0, "usual.phone"), f.previous)
    }

    @Test fun retentionChangeDoesNotRewriteAnAlreadyQueuedHandoff() {
        val f = Fixture()
        f.press(true)
        f.active = true
        f.press(false)
        f.keepDialer = true
        f.active = false
        f.settle()
        assertFalse(f.enabled)
        assertEquals("usual.phone", f.observed.holder)
    }

    @Test fun startupAndExternalRoleSelectionDoNotEnableTakeover() {
        val f = Fixture()
        f.control.applyIfIdle()
        f.observed = f.observed.copy(holder = "gateway")
        f.control.applyIfIdle()
        assertFalse(f.enabled)
        assertTrue(f.events.isEmpty())
    }

    @Test fun enablingDuringExistingWorkWaitsBeforeStartingGatewayAndAcquiringRole() {
        val f = Fixture().apply { active = true }
        f.press(true)
        assertEquals(true, f.pendingSwitch)
        assertFalse(f.enabled)
        assertEquals("usual.phone", f.observed.holder)
        f.active = false
        f.settle()
        assertTrue(f.enabled)
        assertEquals("gateway", f.observed.holder)
    }
}
