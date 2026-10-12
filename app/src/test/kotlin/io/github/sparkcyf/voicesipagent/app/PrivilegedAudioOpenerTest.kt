package io.github.sparkcyf.voicesipagent.app

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.PcmSocketPort
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*

class PrivilegedAudioOpenerTest {
    private val connector = mock(ShizukuConnector::class.java)
    private val service = mock(IPrivilegedService::class.java)
    private val pfd = mock(ParcelFileDescriptor::class.java)
    private val port = mock(PcmSocketPort::class.java)
    private lateinit var log: MockedStatic<Log>

    @Before fun setup() {
        log = mockStatic(Log::class.java)
        doReturn(service).`when`(connector).service
        doReturn(pfd).`when`(service).openCallAudio(AudioMethod.AUTO, 8000)
        doReturn("open").`when`(service).audioStats()
    }
    @After fun teardown() { log.close() }

    private fun opener(factory: (ParcelFileDescriptor, PcmFormat, (String) -> Unit) -> PcmSocketPort = { _, _, _ -> port }) =
        PrivilegedAudioOpener(connector, { AudioMethod.AUTO }, { false }, { PcmFormat.NARROWBAND }, factory)

    @Test fun portConstructionFailureClosesDescriptorAndHelperTap() {
        val result = runCatching { opener { _, _, _ -> error("port construction failed") }.open {} }
        assertTrue(result.isFailure)
        verify(pfd).close()
        verify(service).closeCallAudio()
    }

    @Test fun readerStartupFailureClosesDescriptorAndHelperTap() {
        doThrow(IllegalStateException("reader start failed")).`when`(port).start()
        assertTrue(runCatching { opener().open {} }.isFailure)
        verify(port).close()
        verify(pfd).close()
        verify(service).closeCallAudio()
    }

    @Test fun statsFailureDoesNotLoseOwnershipOfOpenTap() {
        doThrow(IllegalStateException("diagnostics unavailable")).`when`(service).audioStats()
        val handle = opener().open {}
        assertSame(port, handle.port)
        handle.close()
        verify(port).close()
        verify(service).closeCallAudio()
    }

    @Test fun staleHandleCloseDoesNotCloseNewHelperTap() {
        val nextPort = mock(PcmSocketPort::class.java)
        var calls = 0
        val opener = opener { _, _, _ -> if (calls++ == 0) port else nextPort }
        val old = opener.open {}
        val next = opener.open {}
        old.close()
        verify(port).close()
        verify(service, never()).closeCallAudio()
        next.close()
        next.close()
        verify(nextPort).close()
        verify(service).closeCallAudio()
    }

    @Test fun localCloseFailureStillClosesHelperAndIsNotRepeated() {
        doThrow(IllegalStateException("local socket failure")).`when`(port).close()
        val handle = opener().open {}
        assertTrue(runCatching { handle.close() }.isFailure)
        handle.close()
        verify(port).close()
        verify(service).closeCallAudio()
    }
}
