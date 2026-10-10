package com.voicesipagent.telephony

import com.voicesipagent.voice.MediaPort

/** An open cellular audio tap. */
interface CallAudioHandle {
    val port: MediaPort
    fun close()
}

/**
 * Supplies cellular call audio once a call is ACTIVE. Implemented by the privileged (Shizuku)
 * layer; kept as an interface so telephony has no dependency on how audio is obtained.
 */
fun interface CallAudioOpener {
    /** Throws if audio is unavailable; [onLost] fires if the tap dies mid-call. */
    fun open(onLost: (String) -> Unit): CallAudioHandle
}
