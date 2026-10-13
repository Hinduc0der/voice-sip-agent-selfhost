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

    @Test fun dialerSelectionWithoutRoleDoesNotPersistButRoleAcquisitionDoes() {
        val selected = HealthState(effectiveCallAccess = CallAccess.DIALER, holdsDialer = false)
        assertFalse(selected.needsForegroundService)
        assertTrue(selected.copy(holdsDialer = true).needsForegroundService)
        val reminder = selected.copy(holdsDialer = true, takeover = false)
        assertTrue(reminder.needsForegroundService)
        assertTrue(reminder.copy(effectiveCallAccess =
            CallAccess.effective(CallAccess.COMPANION, 34, reminder.holdsDialer)).needsForegroundService)
        assertFalse(reminder.copy(holdsDialer = false).needsForegroundService)
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
            GatewayService.sync(context, dialer.copy(holdsDialer = true, takeover = false))
            GatewayService.sync(context, dialer.copy(holdsDialer = false, takeover = false))
            verify(context, times(3)).startForegroundService(any(Intent::class.java))
            verify(context, times(3)).stopService(any(Intent::class.java))
        }
    }
}
