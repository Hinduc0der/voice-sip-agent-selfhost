package com.voicesipagent.voice

/** Called once per 20 ms frame by a port that owns a real-time audio clock. */
fun interface FrameClockListener {
    fun onFrame()
}

/**
 * The audio side of a [CallLeg], always in [VoiceFormat].
 *
 * A port is either a clock source (e.g. the cellular downlink tap, paced by the modem) or
 * clockless (e.g. RTP, which arrives with network jitter and is smoothed by a jitter buffer).
 * Bridges drive clockless ports from a clock source so the whole path stays in one clock domain.
 */
interface MediaPort {
    /** True if this port ticks [FrameClockListener] at real-time cadence. */
    val isClockSource: Boolean

    /** Only meaningful when [isClockSource]; the listener runs on the port's capture thread. */
    fun setClockListener(listener: FrameClockListener?)

    /** Next frame received from this leg's remote party. Never blocks; returns silence when starved. */
    fun readFrame(): ShortArray

    /** Sends one frame towards this leg's remote party. Must not block for more than a frame. */
    fun writeFrame(frame: ShortArray)
}
