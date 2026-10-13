package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.CallAudioState
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.*

class GatewayInCallServiceTest {
    private val service = mock(GatewayInCallService::class.java, CALLS_REAL_METHODS)

    @Suppress("DEPRECATION")
    private fun audioSnapshot(muted: Boolean) {
        val snapshot = mock(CallAudioState::class.java)
        doReturn(muted).`when`(snapshot).isMuted
        service.onCallAudioStateChanged(snapshot)
    }

    private fun lease() = MuteLease<Any, GatewayInCallService>(
        { it.reportedMuteState }, { svc, value -> svc.setMuted(value) })

    @Test fun initialSnapshotPreservesAnAlreadyMutedMicrophone() {
        val lease = lease()
        val owner = Any()
        assertFalse(lease.acquire(owner, service))
        audioSnapshot(true)
        assertTrue(lease.acquire(owner, service))
        lease.release(owner)
        verify(service, never()).setMuted(anyBoolean())
    }

    @Test fun modernCallbackOverridesSnapshotAndOwnedMuteIsRestored() {
        doNothing().`when`(service).setMuted(anyBoolean())
        val lease = lease()
        val owner = Any()
        audioSnapshot(false)
        assertTrue(lease.acquire(owner, service))
        service.onMuteStateChanged(true)
        audioSnapshot(false) // An older callback cannot overwrite the modern state.
        assertEquals(true, service.reportedMuteState)
        lease.release(owner)
        service.onMuteStateChanged(false)
        assertEquals(false, service.reportedMuteState)
        val changes = inOrder(service)
        changes.verify(service).setMuted(true)
        changes.verify(service).setMuted(false)
    }

    @Test fun modernCallbackBeforeInitialSnapshotRemainsAuthoritative() {
        service.onMuteStateChanged(true)
        audioSnapshot(false)
        assertEquals(true, service.reportedMuteState)
    }
}
