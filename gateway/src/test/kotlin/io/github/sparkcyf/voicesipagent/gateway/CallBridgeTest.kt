package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallBridgeTest {
    private val cell = FakeEndpoint("cellular")
    private val sip = FakeEndpoint("sip")
    private lateinit var bridge: CallBridge

    private fun build(config: GatewayConfig = GatewayConfig(cellularInboundTarget = "302")) {
        bridge = CallBridge(
            sideA = cell,
            sideB = sip,
            routeAtoB = { config.cellularInboundTarget },
            routeBtoA = { it.target },
            config = config,
            dialPolicyForA = { n -> if (n == "911") HangupCause.FORBIDDEN else null },
        )
        bridge.start()
    }

    @Before fun setUp() = build()
    @After fun tearDown() = bridge.stop()

    /** Waits for the bridge thread to drain. */
    private fun settle() {
        repeat(3) {
            val latch = java.util.concurrent.CountDownLatch(1)
            Thread { Thread.sleep(20); latch.countDown() }.start()
            latch.await()
        }
    }

    @Test
    fun cellularInboundToSipFullCall() {
        val a = cell.incoming("13800001234")
        settle()
        val b = sip.dialed.single()
        assertEquals("302", b.target)
        b.remoteRinging(); settle()
        assertTrue("cellular must not be answered before SIP", "answer" !in a.actions)
        b.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)

        val cellPort = FakePort(isClockSource = true, fill = 11)
        val sipPort = FakePort(isClockSource = false, fill = 22)
        a.mediaUp(cellPort); b.mediaUp(sipPort); settle()
        assertNotNull(bridge.session?.media)
        cellPort.clock!!.onFrame()
        assertEquals(11.toShort(), sipPort.written.single()[0])
        assertEquals(22.toShort(), cellPort.written.single()[0])

        b.remoteDtmf('5'); settle()
        assertEquals(listOf('5'), a.dtmf)

        b.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals(LegState.ENDED, a.state)
        assertNull(bridge.session)
        assertNull("clock detached", cellPort.clock)
    }

    @Test
    fun sipFailureRejectsCellular() {
        val a = cell.incoming("13800001234")
        settle()
        sip.dialed.single().remoteEnded(HangupCause.BUSY); settle()
        assertEquals(HangupCause.BUSY, a.endCause)
    }

    @Test
    fun cellularCallerHangsUpBeforeAnswerCancelsSip() {
        val a = cell.incoming("13800001234")
        settle()
        a.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals("hangup:CANCELLED", sip.dialed.single().actions.last())
    }

    @Test
    fun sipInboundDialsCellularAndRingsBack() {
        val a = sip.incoming("302", target = "13800005678")
        settle()
        val b = cell.dialed.single()
        assertEquals("13800005678", b.target)
        assertTrue("180 sent while dialing", "ring" in a.actions)
        b.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)
        a.remoteDtmf('9'); settle()
        assertEquals(listOf('9'), b.dtmf)
    }

    @Test
    fun cellularBusyMapsToSip() {
        cell.onDial = { it.remoteEnded(HangupCause.BUSY) }
        val a = sip.incoming("302", target = "13800005678")
        settle()
        assertEquals(HangupCause.BUSY, a.endCause)
        assertNull(bridge.session)
    }

    @Test
    fun emergencyNumbersAreNeverDialed() {
        val a = sip.incoming("302", target = "911")
        settle()
        assertTrue(cell.dialed.isEmpty())
        assertEquals(HangupCause.FORBIDDEN, a.endCause)
    }

    @Test
    fun prefixAllowList() {
        bridge.stop(); build(GatewayConfig(allowedDialPrefixes = listOf("1")))
        val a = sip.incoming("302", target = "8613800005678")
        settle()
        assertTrue(cell.dialed.isEmpty())
        assertEquals(HangupCause.FORBIDDEN, a.endCause)
    }

    @Test
    fun secondCallWhileBusy() {
        cell.incoming("13800001234")
        settle()
        val second = sip.incoming("302", target = "13800005678")
        settle()
        assertEquals(HangupCause.BUSY, second.endCause)

        bridge.stop(); build(GatewayConfig(busyPolicy = BusyPolicy.IGNORE))
        sip.incoming("302", target = "13800005678"); settle()
        val ignored = cell.incoming("13800009999"); settle()
        assertEquals("native phone keeps it", LegState.PROCEEDING, ignored.state)
    }

    @Test
    fun answerTimeoutEndsBothLegs() {
        bridge.stop(); build(GatewayConfig(cellularInboundTarget = "302", answerTimeoutMs = 50))
        val a = cell.incoming("13800001234")
        Thread.sleep(150); settle()
        assertEquals(HangupCause.NO_ANSWER, a.endCause)
        assertEquals(LegState.ENDED, sip.dialed.single().state)
    }

    @Test
    fun loopbackEchoesCapturedAudio() {
        val port = FakePort(isClockSource = true, fill = 7)
        val mb = MediaBridge.loopback(port).also { it.start() }
        port.clock!!.onFrame()
        port.clock!!.onFrame()
        assertEquals(7.toShort(), port.written.last()[0])
        mb.stop()
    }
}
