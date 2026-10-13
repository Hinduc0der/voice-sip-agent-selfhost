package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState
import org.junit.Assert.*
import org.junit.Test

class DialerRolesTest {
    private class Platform : DialerRoles.Platform {
        var observed = DialerRoleState(10, "old.phone", "system.phone", true, true, false)
        val commands = mutableListOf<Pair<Int, String>>()
        val rejected = mutableSetOf<String>()
        var afterCommand: (() -> Unit)? = null
        var uncertain = false
        var removalHolder = "system.phone"
        override fun state() = observed
        override fun addHolder(userId: Int, packageName: String): ShellEnv.Result {
            commands += userId to packageName
            val result = when {
                uncertain -> ShellEnv.Result(-1, "TimeoutException")
                packageName in rejected -> ShellEnv.Result(-1, "Package does not qualify")
                else -> { observed = observed.copy(holder = packageName); ShellEnv.Result(0, "") }
            }
            afterCommand?.invoke()
            return result
        }
        override fun removeHolder(userId: Int, packageName: String): ShellEnv.Result {
            commands += userId to "remove:$packageName"
            observed = observed.copy(holder = removalHolder)
            return ShellEnv.Result(0, "")
        }
    }

    @Test fun acquisitionUsesTheAppUserAndVerifiesTheHolder() {
        val platform = Platform()
        val result = DialerRoles("gateway", platform).acquire("old.phone")
        assertTrue(result.success)
        assertEquals("gateway", result.state?.holder)
        assertEquals(listOf(10 to "gateway"), platform.commands)
    }

    @Test fun repeatedAcquisitionDoesNotRunAnotherCommand() {
        val platform = Platform().apply { observed = observed.copy(holder = "gateway") }
        assertTrue(DialerRoles("gateway", platform).acquire("old.phone").success)
        assertTrue(platform.commands.isEmpty())
    }

    @Test fun changedHolderAndUnverifiedIdleStateNeverMutateRoles() {
        val platform = Platform()
        val roles = DialerRoles("gateway", platform)
        assertEquals(DialerRoleResult.CHANGED, roles.acquire("different.phone").status)
        platform.observed = platform.observed.copy(inCall = null)
        assertEquals(DialerRoleResult.FAILED, roles.acquire("old.phone").status)
        platform.observed = platform.observed.copy(inCall = true)
        assertTrue(roles.acquire("old.phone").deferred)
        platform.observed = platform.observed.copy(inCall = false, canManage = false)
        assertEquals(DialerRoleResult.FAILED, roles.acquire("old.phone").status)
        assertTrue(platform.commands.isEmpty())
    }

    @Test fun restorePrefersTheSavedDialerOverTheSystemDefault() {
        val platform = Platform().apply { observed = observed.copy(holder = "gateway") }
        val result = DialerRoles("gateway", platform).restore("old.phone")
        assertTrue(result.success)
        assertEquals("old.phone", result.state?.holder)
        assertEquals(listOf(10 to "old.phone"), platform.commands)
    }

    @Test fun unqualifiedPreviousDialerFallsBackToTheSystemDialer() {
        val platform = Platform().apply { observed = observed.copy(holder = "gateway"); rejected += "old.phone" }
        val result = DialerRoles("gateway", platform).restore("old.phone")
        assertTrue(result.success)
        assertEquals("system.phone", result.state?.holder)
        assertEquals(listOf(10 to "old.phone", 10 to "system.phone"), platform.commands)
    }

    @Test fun aCallStartingDuringFailedRestorationDefersTheFallback() {
        val platform = Platform().apply {
            observed = observed.copy(holder = "gateway")
            rejected += "old.phone"
            afterCommand = { observed = observed.copy(inCall = true) }
        }
        assertTrue(DialerRoles("gateway", platform).restore("old.phone").deferred)
        assertEquals(listOf(10 to "old.phone"), platform.commands)
    }

    @Test fun aConcurrentUserSelectionStopsFallbackAttempts() {
        val platform = Platform().apply {
            observed = observed.copy(holder = "gateway")
            rejected += "old.phone"
            afterCommand = { observed = observed.copy(holder = "chosen.phone") }
        }
        assertFalse(DialerRoles("gateway", platform).restore("old.phone").success)
        assertEquals("chosen.phone", platform.observed.holder)
        assertEquals(listOf(10 to "old.phone"), platform.commands)
    }

    @Test fun timeoutDoesNotTriggerAnotherRoleMutation() {
        val platform = Platform().apply { observed = observed.copy(holder = "gateway"); uncertain = true }
        assertFalse(DialerRoles("gateway", platform).restore("old.phone").success)
        assertEquals(listOf(10 to "old.phone"), platform.commands)
    }

    @Test fun removalRequiresAnObservedReplacementToReportSuccess() {
        val platform = Platform().apply {
            observed = observed.copy(holder = "gateway", fallback = "")
            removalHolder = ""
        }
        assertFalse(DialerRoles("gateway", platform).restore("").success)
        assertEquals(listOf(10 to "remove:gateway"), platform.commands)
        platform.observed = platform.observed.copy(holder = "gateway")
        platform.removalHolder = "vendor.phone"
        assertEquals("vendor.phone", DialerRoles("gateway", platform).restore("").state?.holder)
    }
}
