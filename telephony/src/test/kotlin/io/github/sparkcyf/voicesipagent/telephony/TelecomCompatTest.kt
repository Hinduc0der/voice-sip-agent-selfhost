package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class TelecomCompatTest {
    @Test fun android12ReadsDetailsWithoutCallingLegacyGetter() {
        val call = mock(Call::class.java)
        val details = mock(Call.Details::class.java)
        doReturn(details).`when`(call).details
        doReturn(Call.STATE_RINGING).`when`(details).state
        assertEquals(Call.STATE_RINGING, callState(call, 31))
        // Only the Details getter should be consulted; the legacy state getter returns zero.
        verify(call).details
        verifyNoMoreInteractions(call)
    }

    @Suppress("DEPRECATION")
    @Test fun android11ReadsLegacyStateWithoutCallingDetailsGetter() {
        val call = mock(Call::class.java)
        doReturn(Call.STATE_ACTIVE).`when`(call).state
        assertEquals(Call.STATE_ACTIVE, callState(call, 30))
        verify(call).state
        verifyNoMoreInteractions(call)
    }
}
