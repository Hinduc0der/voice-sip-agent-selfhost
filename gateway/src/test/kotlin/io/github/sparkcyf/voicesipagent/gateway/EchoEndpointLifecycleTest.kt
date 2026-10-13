package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.*
import org.junit.Test

class EchoEndpointLifecycleTest {
    @Test fun disablingLoopbackCancelsPendingAnswerAndReleasesItsTimerThread() {
        val existing = Thread.getAllStackTraces().keys
        val endpoint = EchoEndpoint(answerDelayMs = 10_000)
        val timer = Thread.getAllStackTraces().keys.single { it !in existing && it.name == "echo-endpoint" }
        val leg = endpoint.dial("test", Party(null))
        endpoint.close()
        endpoint.close()
        timer.join(2000)
        assertFalse(timer.isAlive)
        assertFalse(endpoint.status.ready)
        assertEquals(LegState.RINGING, leg.state)
        assertNull(leg.media)
        assertEquals(LegState.ENDED, endpoint.dial("test", Party(null)).state)
    }
}
