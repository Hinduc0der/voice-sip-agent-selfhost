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
import java.util.ArrayDeque
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule

/**
 * Test endpoint: every dialed leg answers after [answerDelayMs] and plays back whatever it hears
 * [echoDelayMs] later. Bridging the cellular endpoint to this verifies the audio tap end to end.
 */
class EchoEndpoint(
    private val answerDelayMs: Long = 1000,
    private val echoDelayMs: Int = 200,
) : CallEndpoint {
    override val name = "echo"
    override val status = EndpointStatus(true, "echo test")
    private val timer = Timer("echo-endpoint", true)
    private val ids = AtomicInteger(1)

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {}
    override fun addStatusListener(listener: EndpointStatusListener) {}
    override fun removeStatusListener(listener: EndpointStatusListener) {}

    override fun dial(target: String, caller: Party): CallLeg {
        val leg = EchoLeg("e${ids.getAndIncrement()}", target, echoDelayMs / VoiceFormat.FRAME_MS)
        leg.proceed()
        timer.schedule(answerDelayMs) { leg.connect() }
        return leg
    }

    private class EchoLeg(id: String, target: String, delayFrames: Int) :
        AbstractCallLeg(id, "echo", LegDirection.OUTBOUND, Party(target), target) {
        private val port = DelayPort(delayFrames)
        fun proceed() = transition(LegState.RINGING)
        fun connect() {
            if (transition(LegState.CONNECTED)) publishMedia(port)
        }
        override fun ring() {}
        override fun answer() {}
        override fun hangup(cause: HangupCause) { transition(LegState.ENDED, cause) }
        override fun sendDtmf(digit: Char, durationMs: Int) {}
    }

    private class DelayPort(private val frames: Int) : MediaPort {
        private val line = ArrayDeque<ShortArray>()
        override val isClockSource = false
        override fun setClockListener(listener: FrameClockListener?) {}
        @Synchronized override fun readFrame(): ShortArray =
            if (line.size > frames) line.poll() else VoiceFormat.silence()
        @Synchronized override fun writeFrame(frame: ShortArray) {
            line.add(frame)
            while (line.size > frames + 5) line.poll()
        }
    }
}
