package io.github.sparkcyf.voicesipagent.privileged

import android.os.Bundle

data class DialerRoleState(
    val userId: Int,
    val holder: String,
    val fallback: String,
    val available: Boolean,
    val canManage: Boolean,
    /** Null means the helper could not establish whether Telecom is idle. */
    val inCall: Boolean?,
)

data class DialerRoleResult(val status: String, val message: String, val state: DialerRoleState? = null) {
    val success get() = status == SUCCESS
    val deferred get() = status == BUSY

    fun toBundle() = Bundle().apply {
        putString("status", status)
        putString("message", message)
        state?.let {
            putInt("user_id", it.userId)
            putString("holder", it.holder)
            putString("fallback", it.fallback)
            putBoolean("available", it.available)
            putBoolean("can_manage", it.canManage)
            it.inCall?.let { busy -> putBoolean("in_call", busy) }
        }
    }

    companion object {
        const val STATE = "state"
        const val SUCCESS = "success"
        const val BUSY = "busy"
        const val CHANGED = "changed"
        const val FAILED = "failed"

        fun fromBundle(bundle: Bundle) = DialerRoleResult(bundle.getString("status") ?: FAILED,
            bundle.getString("message").orEmpty(), if (!bundle.containsKey("user_id")) null else DialerRoleState(
                bundle.getInt("user_id"), bundle.getString("holder").orEmpty(), bundle.getString("fallback").orEmpty(),
                bundle.getBoolean("available"), bundle.getBoolean("can_manage"),
                if (bundle.containsKey("in_call")) bundle.getBoolean("in_call") else null))
    }
}
