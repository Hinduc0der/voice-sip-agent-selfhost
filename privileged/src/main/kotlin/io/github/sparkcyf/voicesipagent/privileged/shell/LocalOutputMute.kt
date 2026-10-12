package io.github.sparkcyf.voicesipagent.privileged.shell

import android.media.AudioManager

/** Owns only our output-mute changes. The caller serializes access and ties restoration to the tap. */
internal class LocalOutputMute(private val audio: AudioControl) {
    interface AudioControl {
        val mode: Int
        val voiceCallMuted: Boolean
        fun requestMode(mode: Int)
        fun requestVoiceCallMute(muted: Boolean)
        /** Return the observed state after a bounded wait; -1 means let the mode settle. */
        fun awaitMode(wanted: Int): Int
        fun awaitVoiceCallMute(wanted: Boolean): Boolean
    }

    private enum class Method { REDIRECT_MODE, VOICE_CALL_STREAM, PRESERVE_STREAM_MUTE }
    private var method: Method? = null

    fun mute(useRedirectMode: Boolean): String = reportFailure("mute") {
        method?.let {
            val stillMuted = when (it) {
                Method.REDIRECT_MODE -> audio.mode == MODE_CALL_REDIRECT
                else -> audio.voiceCallMuted
            }
            return@reportFailure if (stillMuted) "already muted: ${description(it)}"
                else "failed: ${description(it)} no longer muted; restoration pending"
        }
        val before = audio.mode
        if (before != AudioManager.MODE_IN_CALL) return@reportFailure "skipped: mode=$before"
        if (useRedirectMode) {
            // Record ownership before calling: a request may apply even if its acknowledgement fails.
            method = Method.REDIRECT_MODE
            audio.requestMode(MODE_CALL_REDIRECT)
            val after = audio.awaitMode(MODE_CALL_REDIRECT)
            if (after == MODE_CALL_REDIRECT) "muted: mode $before->$after"
            else "failed: redirection mode not applied (mode=$after); restoration pending"
        } else if (audio.voiceCallMuted) {
            method = Method.PRESERVE_STREAM_MUTE
            "already muted: voice-call stream (preserved)"
        } else {
            method = Method.VOICE_CALL_STREAM
            audio.requestVoiceCallMute(true)
            if (audio.awaitVoiceCallMute(true)) "muted: voice-call stream"
            else "failed: voice-call stream mute not applied; restoration pending"
        }
    }

    fun restore(why: String): String = reportFailure("restore") {
        val owned = method ?: return@reportFailure "not muted"
        when (owned) {
            Method.PRESERVE_STREAM_MUTE -> {
                method = null
                "preserved existing voice-call stream mute ($why)"
            }
            Method.REDIRECT_MODE -> {
                // NORMAL withdraws our mode request, allowing Telecom's request to take over.
                audio.requestMode(AudioManager.MODE_NORMAL)
                val after = audio.awaitMode(-1)
                if (after == MODE_CALL_REDIRECT) {
                    "failed: redirection mode still active ($why); restoration pending"
                } else {
                    method = null
                    "restored redirection mode ($why), mode=$after"
                }
            }
            Method.VOICE_CALL_STREAM -> {
                // Always undo an owned request, even if the mute was not observed yet. Do not change
                // the volume index: ADJUST_UNMUTE restores output at the user's current call volume.
                audio.requestVoiceCallMute(false)
                if (audio.awaitVoiceCallMute(false)) {
                    "failed: voice-call stream still muted ($why); restoration pending"
                } else {
                    method = null
                    "restored voice-call stream ($why)"
                }
            }
        }
    }

    private inline fun reportFailure(action: String, block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        "failed: $action ${e.javaClass.simpleName}: ${e.message}" +
            if (method != null) "; restoration pending" else ""
    }

    private fun description(method: Method): String = when (method) {
        Method.REDIRECT_MODE -> "redirection mode"
        Method.VOICE_CALL_STREAM -> "voice-call stream"
        Method.PRESERVE_STREAM_MUTE -> "voice-call stream (preserved)"
    }

    companion object {
        /** AudioManager.MODE_CALL_REDIRECT (API 33, system API). */
        const val MODE_CALL_REDIRECT = 5
    }
}
