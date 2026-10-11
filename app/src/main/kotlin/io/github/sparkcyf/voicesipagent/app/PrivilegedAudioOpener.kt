package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.PcmSocketPort
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.CallAudioHandle
import io.github.sparkcyf.voicesipagent.telephony.CallAudioOpener
import io.github.sparkcyf.voicesipagent.voice.PcmFormat

/** Glue: the telephony layer's audio source, backed by the Shizuku shell service. */
class PrivilegedAudioOpener(
    private val shizuku: ShizukuConnector,
    private val method: () -> Int,
    private val muteEarpiece: () -> Boolean,
    private val format: () -> PcmFormat,
) : CallAudioOpener {
    override fun open(onLost: (String) -> Unit): CallAudioHandle {
        val svc = shizuku.service ?: error("privileged service not connected (${shizuku.state})")
        val pcm = format()
        val pfd = svc.openCallAudio(method(), pcm.sampleRate)
        val port = PcmSocketPort(pfd, pcm, onLost).also { it.start() }
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
