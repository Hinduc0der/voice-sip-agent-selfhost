package io.github.sparkcyf.voicesipagent.privileged

/** Bundle keys returned by [IPrivilegedService.health]. */
object HealthKeys {
    const val UID = "uid"
    const val MANAGE_ONGOING_CALLS = "manage_ongoing_calls"
    const val COMPANION_OVERRIDE = "companion_override"
    const val INTERCEPTABLE = "pstn_interceptable"
    const val DOZE_ALLOWLISTED = "doze_allowlisted"
    const val DETAIL = "detail"
    const val DIRECT_AUDIO = "direct_audio"
    const val REDIRECT_AUDIO = "redirect_audio"
    const val LOCAL_SUPPRESSION = "local_suppression"
    const val AUDIO_DETAIL = "audio_detail"

    val BOOLEAN_KEYS = listOf(MANAGE_ONGOING_CALLS, COMPANION_OVERRIDE, INTERCEPTABLE, DOZE_ALLOWLISTED,
        DIRECT_AUDIO, REDIRECT_AUDIO, LOCAL_SUPPRESSION)
}

object CallAccess {
    const val AUTO = 0
    const val COMPANION = 1
    const val DIALER = 2

    fun effective(selected: Int, sdk: Int, holdsDialer: Boolean): Int = when (selected) {
        COMPANION -> if (holdsDialer) DIALER else COMPANION
        DIALER -> DIALER
        else -> if (holdsDialer || sdk == 30) DIALER else COMPANION
    }
}

object AudioMethod {
    const val AUTO = 0
    const val OFFICIAL = 1
    const val LEGACY = 2
}
