package io.github.sparkcyf.voicesipagent.app

import android.content.Context

/** Separate from SIP settings so role changes never reseal credentials or reset a SIP account. */
internal class DialerHandoffStore(context: Context) : DialerHandoff.Store {
    private val preferences = context.getSharedPreferences("dialer_handoff", Context.MODE_PRIVATE)

    override fun load(): PreviousDialer? = if (!preferences.contains("previous_package")) null else
        PreviousDialer(preferences.getInt("user_id", -1), preferences.getString("previous_package", "").orEmpty())

    // Persist before asking Android to change the role; that operation can kill either dialer.
    override fun save(previous: PreviousDialer) = preferences.edit()
        .putInt("user_id", previous.userId).putString("previous_package", previous.packageName).commit()

    override fun clear() = preferences.edit().remove("user_id").remove("previous_package").commit()
}
