package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.os.Build

/** Shared Telecom callbacks; this component is a companion and its dialer subclass provides UI. */
open class GatewayInCallService : InCallService() {
    internal var reportedMuteState: Boolean? = null
        private set
    internal val microphoneMuted: Boolean?
        get() = if (Build.VERSION.SDK_INT >= 34) reportedMuteState else legacyMicrophoneMuted()

    @Suppress("DEPRECATION")
    private fun legacyMicrophoneMuted(): Boolean? = callAudioState?.isMuted
    protected open val isDialerService = false
    internal val serviceDescription: String
        get() = if (isDialerService) "default-dialer InCallService" else "companion InCallService"
    override fun onCreate() {
        super.onCreate()
        CellularEndpoint.attachManagedService(this, isDialerService)
    }

    override fun onCallAdded(call: Call) {
        CellularEndpoint.current?.onCallAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        CellularEndpoint.current?.onCallRemoved(this, call)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        // The initial snapshot still arrives here on API 34+, before any mute change event.
        if (reportedMuteState == null) reportedMuteState = audioState.isMuted
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        reportedMuteState = isMuted
    }

    override fun onDestroy() {
        try { CellularEndpoint.detachManagedService(this) } finally { super.onDestroy() }
    }
}
