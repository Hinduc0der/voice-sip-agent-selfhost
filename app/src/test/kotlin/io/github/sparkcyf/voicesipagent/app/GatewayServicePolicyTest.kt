package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.Intent
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class GatewayServicePolicyTest {
    @Test fun companionStandbyPersistsButSwitchingOffStopsService() {
        val standby = HealthState(effectiveCallAccess = CallAccess.COMPANION, takeover = true)
        assertTrue(standby.needsForegroundService)
        assertFalse(standby.copy(takeover = false).needsForegroundService)
    }

    @Test fun pendingTakeoverOffKeepsServiceUntilCallCleanupEnds() {
        val pending = HealthState(effectiveCallAccess = CallAccess.COMPANION,
            takeover = true, takeoverOffPending = true, session = "gateway call")
        assertTrue(pending.needsForegroundService)
        assertFalse(pending.copy(takeover = false, takeoverOffPending = false, session = null).needsForegroundService)
    }

    @Test fun dialerSelectionWithoutRoleDoesNotPersistButRoleAcquisitionDoes() {
        val selected = HealthState(effectiveCallAccess = CallAccess.DIALER, holdsDialer = false)
        assertFalse(selected.needsForegroundService)
        assertTrue(selected.copy(holdsDialer = true).needsForegroundService)
        // Keep the recovery screen available for the actual default dialer even with takeover off.
        assertTrue(selected.copy(holdsDialer = true, takeover = false).needsForegroundService)
    }

    @Test fun losingRoleKeepsOwnedCallCleanupButOtherCallsDoNotKeepServiceAlive() {
        val lost = HealthState(effectiveCallAccess = CallAccess.DIALER, holdsDialer = false,
            calls = CallSnapshot(count = 1, owned = 1))
        assertTrue(lost.needsForegroundService)
        assertFalse(lost.copy(calls = CallSnapshot(count = 1, owned = 0)).needsForegroundService)
        assertFalse(lost.copy(calls = CallSnapshot()).needsForegroundService)
        assertTrue(lost.copy(calls = CallSnapshot(), session = "SIP call finishing").needsForegroundService)
    }

    @Test fun switchAndRoleChangesRequestServiceStopAndRestart() {
        val context = mock(Context::class.java)
        mockConstruction(Intent::class.java).use {
            val companion = HealthState(effectiveCallAccess = CallAccess.COMPANION)
            GatewayService.sync(context, companion)
            GatewayService.sync(context, companion.copy(takeover = false))
            val dialer = companion.copy(effectiveCallAccess = CallAccess.DIALER, holdsDialer = false)
            GatewayService.sync(context, dialer)
            GatewayService.sync(context, dialer.copy(holdsDialer = true))
            verify(context, times(2)).startForegroundService(any(Intent::class.java))
            verify(context, times(2)).stopService(any(Intent::class.java))
        }
    }
}
