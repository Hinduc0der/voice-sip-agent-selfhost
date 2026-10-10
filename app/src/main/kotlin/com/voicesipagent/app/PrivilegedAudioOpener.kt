package com.voicesipagent.app

import com.voicesipagent.privileged.PcmSocketPort
import com.voicesipagent.privileged.ShizukuConnector
import com.voicesipagent.telephony.CallAudioHandle
import com.voicesipagent.telephony.CallAudioOpener

/** Glue: the telephony layer's audio source, backed by the Shizuku shell service. */
class PrivilegedAudioOpener(
    private val shizuku: ShizukuConnector,
    private val method: () -> Int,
    private val muteEarpiece: () -> Boolean,
) : CallAudioOpener {
    override fun open(onLost: (String) -> Unit): CallAudioHandle {
        val svc = shizuku.service ?: error("privileged service not connected (${shizuku.state})")
        val pfd = svc.openCallAudio(method())
        val port = PcmSocketPort(pfd, onLost).also { it.start() }
        AppLog.i("call audio tap opened: ${svc.audioStats()}")
        if (muteEarpiece()) {
            // Best effort: a failure here only means the handset stays audible.
            AppLog.i("earpiece mute: ${runCatching { svc.setLocalOutputMuted(true) }.getOrElse { "failed: ${it.message}" }}")
        }
        return object : CallAudioHandle {
            override val port = port
            override fun close() {
                port.close()
                runCatching { svc.closeCallAudio() }
                AppLog.i("call audio tap closed: in=${port.framesIn} out=${port.framesOut} ${runCatching { svc.audioStats() }.getOrDefault("")}")
            }
        }
    }
}
