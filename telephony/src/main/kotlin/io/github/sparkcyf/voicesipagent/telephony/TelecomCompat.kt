package io.github.sparkcyf.voicesipagent.telephony

import android.os.Build
import android.telecom.Call

/** Call.Details gained the state getter in Android 12. */
val Call.telecomState: Int get() = callState(this, Build.VERSION.SDK_INT)

internal fun callState(call: Call, sdk: Int): Int =
    if (sdk >= 31) call.details.state else legacyCallState(call)

@Suppress("DEPRECATION")
private fun legacyCallState(call: Call): Int = call.state

