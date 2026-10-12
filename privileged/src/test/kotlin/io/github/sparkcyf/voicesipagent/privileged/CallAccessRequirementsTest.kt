package io.github.sparkcyf.voicesipagent.privileged

import org.junit.Assert.*
import org.junit.Test

class CallAccessRequirementsTest {
    private val dialer = CallAccessRequirements(CallAccess.DIALER, 30, true, true,
        true, false, false, false, true)

    @Test fun dialerIsReadyWithoutCompanionPrivilegesOrAnIdleBinding() {
        assertTrue(dialer.ready)
        assertNull(dialer.problem)
    }

    @Test fun declinedOrRevokedRolePreventsCallAdmission() {
        val revoked = dialer.copy(holdsDialer = false)
        assertFalse(revoked.ready)
        assertTrue(revoked.problem!!.startsWith("Select this app"))
    }

    @Test fun disabledRoleComponentAndRevokedCallingPermissionAreReportedSeparately() {
        assertTrue(dialer.copy(dialerComponents = false).problem!!.contains("activity or service"))
        assertTrue(dialer.copy(callingPermissions = false).problem!!.contains("permissions"))
    }

    @Test fun unavailableRoleReportsRecoveryInsteadOfRequestingCompanionSetup() {
        assertTrue(dialer.copy(holdsDialer = false, roleAvailable = false).problem!!.contains("unavailable"))
    }

    @Test fun companionRequiresSupportedReleaseComponentAndSetup() {
        val companion = dialer.copy(effective = CallAccess.COMPANION, holdsDialer = false,
            sdk = 31, companionComponent = true, companionAppOp = true, companionOverride = true)
        assertTrue(companion.ready)
        assertFalse(companion.copy(sdk = 30).ready)
        assertFalse(companion.copy(companionComponent = false).ready)
        assertFalse(companion.copy(companionAppOp = false).ready)
        assertFalse(companion.copy(companionOverride = false).ready)
    }

    @Test fun automaticModePreservesModernCompanionAndRequiresExplicitDialerSelectionOn30() {
        assertEquals(CallAccess.DIALER, CallAccess.effective(CallAccess.AUTO, 30, false))
        assertEquals(CallAccess.COMPANION, CallAccess.effective(CallAccess.AUTO, 34, false))
        assertEquals(CallAccess.DIALER, CallAccess.effective(CallAccess.AUTO, 34, true))
        // A preference does not silently revoke a role the user has already granted.
        assertEquals(CallAccess.DIALER, CallAccess.effective(CallAccess.COMPANION, 34, true))
    }
}
