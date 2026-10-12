package io.github.sparkcyf.voicesipagent.telephony

import io.github.sparkcyf.voicesipagent.voice.MediaPort
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class AudioLeaseTest {
    private class Handle(private val failClose: Boolean = false) : CallAudioHandle {
        override val port = mock(MediaPort::class.java)
        var closes = 0
        override fun close() { closes++; if (failClose) error("close failed") }
    }

    @Test fun lateOpenAfterHangupIsClosedWithoutInstallation() {
        val lease = AudioLease()
        val ticket = lease.begin()
        lease.invalidate()
        val late = Handle()
        assertFalse(lease.install(ticket, late))
        assertEquals(1, late.closes)
        assertNull(lease.handle)
    }

    @Test fun oldLossCannotInvalidateNewAttempt() {
        val lease = AudioLease()
        val old = lease.begin()
        val next = lease.begin()
        val opened = Handle()
        assertTrue(lease.install(next, opened))
        assertFalse(lease.isCurrent(old))
        val late = Handle()
        assertFalse(lease.install(old, late))
        assertSame(opened, lease.handle)
        assertEquals(0, opened.closes)
        assertEquals(1, late.closes)
    }

    @Test fun throwingCloseStillInvalidatesLeaseAndIsNotRepeated() {
        val failures = mutableListOf<Throwable>()
        val lease = AudioLease { failures += it }
        val ticket = lease.begin()
        val opened = Handle(failClose = true)
        lease.install(ticket, opened)
        lease.invalidate()
        lease.invalidate()
        assertEquals(1, opened.closes)
        assertEquals(1, failures.size)
        assertFalse(lease.isCurrent(ticket))
        assertNull(lease.handle)
    }

    @Test fun replacementClosesOldHandleAndDuplicateInstallDoesNotCloseCurrent() {
        val lease = AudioLease()
        val first = Handle()
        val ticket = lease.begin()
        lease.install(ticket, first)
        assertTrue(lease.install(ticket, first))
        val next = lease.begin()
        assertEquals(1, first.closes)
        val second = Handle()
        lease.install(next, second)
        lease.invalidate()
        assertEquals(1, second.closes)
    }
}
