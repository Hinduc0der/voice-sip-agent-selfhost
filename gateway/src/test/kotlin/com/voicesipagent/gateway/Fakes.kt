package com.voicesipagent.gateway

import com.voicesipagent.voice.AbstractCallLeg
import com.voicesipagent.voice.CallEndpoint
import com.voicesipagent.voice.CallLeg
import com.voicesipagent.voice.EndpointStatus
import com.voicesipagent.voice.EndpointStatusListener
import com.voicesipagent.voice.FrameClockListener
import com.voicesipagent.voice.HangupCause
import com.voicesipagent.voice.IncomingCallHandler
import com.voicesipagent.voice.LegDirection
import com.voicesipagent.voice.LegState
import com.voicesipagent.voice.MediaPort
import com.voicesipagent.voice.Party
import com.voicesipagent.voice.VoiceFormat
import java.util.concurrent.CopyOnWriteArrayList

class FakePort(override val isClockSource: Boolean, private val fill: Short) : MediaPort {
    var clock: FrameClockListener? = null
    val written = CopyOnWriteArrayList<ShortArray>()
    override fun setClockListener(listener: FrameClockListener?) { clock = listener }
    override fun readFrame() = ShortArray(VoiceFormat.FRAME_SAMPLES) { fill }
    override fun writeFrame(frame: ShortArray) { written += frame }
}

class FakeLeg(kind: String, dir: LegDirection, remote: Party, target: String?) :
    AbstractCallLeg("$kind-${target ?: remote.number}", kind, dir, remote, target) {
    val actions = CopyOnWriteArrayList<String>()
    val dtmf = CopyOnWriteArrayList<Char>()

    override fun ring() { actions += "ring"; transition(LegState.RINGING) }
    override fun answer() { actions += "answer"; transition(LegState.CONNECTED) }
    override fun hangup(cause: HangupCause) { actions += "hangup:$cause"; transition(LegState.ENDED, cause) }
    override fun sendDtmf(digit: Char, durationMs: Int) { dtmf += digit }

    // Test drivers, simulating the remote side / network
    fun remoteProceeding() = transition(LegState.PROCEEDING)
    fun remoteRinging() = transition(LegState.RINGING)
    fun remoteAnswered() = transition(LegState.CONNECTED)
    fun remoteEnded(cause: HangupCause) = transition(LegState.ENDED, cause)
    fun mediaUp(port: MediaPort) = publishMedia(port)
    fun remoteDtmf(c: Char) = publishDtmf(c, 100)
}

class FakeEndpoint(override val name: String) : CallEndpoint {
    var handler: IncomingCallHandler? = null
    val dialed = CopyOnWriteArrayList<FakeLeg>()
    var onDial: (FakeLeg) -> Unit = { it.remoteProceeding() }
    override val status = EndpointStatus(true, "ok")
    override fun setIncomingCallHandler(handler: IncomingCallHandler?) { this.handler = handler }
    override fun addStatusListener(listener: EndpointStatusListener) {}
    override fun removeStatusListener(listener: EndpointStatusListener) {}
    override fun dial(target: String, caller: Party): CallLeg {
        val leg = FakeLeg(name, LegDirection.OUTBOUND, Party(target), target)
        dialed += leg
        onDial(leg)
        return leg
    }

    fun incoming(number: String, target: String? = null): FakeLeg {
        val leg = FakeLeg(name, LegDirection.INBOUND, Party(number), target)
        leg.remoteProceeding()
        handler!!.onIncomingCall(leg)
        return leg
    }
}
