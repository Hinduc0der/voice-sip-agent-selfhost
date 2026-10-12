package io.github.sparkcyf.voicesipagent.telephony

import org.junit.Assert.*
import org.junit.Test

class MuteLeaseTest {
    private class Service(var muted: Boolean?) {
        val changes = mutableListOf<Boolean>()
        var fail = false
        fun set(value: Boolean) { changes += value; if (fail) error("service died") }
    }
    private fun lease() = MuteLease<Any, Service>({ it.muted }, { svc, value -> svc.set(value) })

    @Test fun originalMuteIsPreserved() {
        val service = Service(true)
        val lease = lease()
        val owner = Any()
        assertTrue(lease.acquire(owner, service))
        lease.release(owner)
        assertTrue(service.changes.isEmpty())
    }

    @Test fun cancellationRestoresEvenBeforeMuteCallbackArrives() {
        val service = Service(false)
        val lease = lease()
        val owner = Any()
        lease.acquire(owner, service)
        // Service still reports false because Telecom applies changes asynchronously.
        lease.release(owner)
        lease.release(owner)
        assertEquals(listOf(true, false), service.changes)
    }

    @Test fun oldOwnerCannotUnmuteReplacement() {
        val first = Service(false)
        val next = Service(false)
        val oldOwner = Any()
        val nextOwner = Any()
        val lease = lease()
        lease.acquire(oldOwner, first)
        lease.release(oldOwner)
        lease.acquire(nextOwner, next)
        lease.release(oldOwner)
        assertEquals(listOf(true), next.changes)
        lease.release(nextOwner)
        assertEquals(listOf(true, false), next.changes)
    }

    @Test fun failedRestoreDoesNotRetainOwnership() {
        val service = Service(false)
        val lease = lease()
        val owner = Any()
        lease.acquire(owner, service)
        service.fail = true
        assertTrue(runCatching { lease.release(owner) }.isFailure)
        val replacement = Service(false)
        assertTrue(lease.acquire(Any(), replacement))
    }

    @Test fun failedAcquireCanStillRestoreAnAcceptedButUnacknowledgedChange() {
        val service = Service(false)
        val lease = lease()
        val owner = Any()
        service.fail = true
        assertTrue(runCatching { lease.acquire(owner, service) }.isFailure)
        service.fail = false
        lease.release(owner)
        assertEquals(listOf(true, false), service.changes)
    }

    @Test fun missingStateRefusesMuteAndRepeatedAcquireDoesNotOverwriteOriginal() {
        val service = Service(null)
        val lease = lease()
        val owner = Any()
        assertFalse(lease.acquire(owner, service))
        service.muted = false
        assertTrue(lease.acquire(owner, service))
        service.muted = true
        assertTrue(lease.acquire(owner, service))
        assertFalse(lease.acquire(Any(), service))
        lease.release(owner)
        assertEquals(listOf(true, false), service.changes)
    }
}
