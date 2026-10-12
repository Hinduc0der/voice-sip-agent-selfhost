package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build

internal data class AudioCapabilities(val direct: Boolean, val redirect: Boolean, val suppression: Boolean, val detail: String) {
    companion object {
        fun inspect(ctx: Context): AudioCapabilities {
            val failures = mutableListOf<String>()
            val attribution = runCatching { ShellEnv.prepareAudioAttribution(ctx) }.onFailure {
                failures += "audio attribution: ${it.message}"
            }.isSuccess
            fun permission(name: String): Boolean = ShellEnv.hasPermission("android.permission.$name", ctx)
            val directMissing = listOf("RECORD_AUDIO", "CAPTURE_AUDIO_OUTPUT", "MODIFY_PHONE_STATE").filterNot(::permission)
            if (directMissing.isNotEmpty()) failures += "direct audio missing: ${directMissing.joinToString()}"
            val redirectionApi = Build.VERSION.SDK_INT >= 33 && runCatching {
                AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
                AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            }.isSuccess
            val redirect = attribution && redirectionApi && permission("CALL_AUDIO_INTERCEPTION")
            if (!redirectionApi) failures += "call redirection API unavailable"
            else if (!redirect) failures += "CALL_AUDIO_INTERCEPTION unavailable"
            val direct = attribution && directMissing.isEmpty()
            // A preflight permission check, not proof of handset silence: vendor policy/HAL may
            // reject stream mute. Direct taps use it even before the redirection APIs exist.
            val suppression = (direct || redirect) && permission("MODIFY_PHONE_STATE")
            return AudioCapabilities(direct, redirect, suppression, failures.joinToString("; "))
        }
    }
}
