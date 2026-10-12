package io.github.sparkcyf.voicesipagent.privileged

/** Configuration readiness is independent of Telecom binding, which normally begins with a call. */
data class CallAccessRequirements(
    val effective: Int,
    val sdk: Int,
    val holdsDialer: Boolean,
    val roleAvailable: Boolean,
    val dialerComponents: Boolean,
    val companionComponent: Boolean,
    val companionAppOp: Boolean,
    val companionOverride: Boolean,
    val callingPermissions: Boolean,
) {
    val problem: String? get() = when {
        effective == CallAccess.DIALER && !holdsDialer && !roleAvailable -> "Default dialer selection is unavailable on this device"
        effective == CallAccess.DIALER && !holdsDialer -> "Select this app as default dialer to enable gateway calls"
        effective == CallAccess.DIALER && !dialerComponents -> "Default dialer activity or service is unavailable"
        effective == CallAccess.COMPANION && sdk < 31 -> "Companion access unavailable on Android 11; select default-dialer mode"
        effective == CallAccess.COMPANION && !companionComponent -> "Companion call service is disabled"
        // Our service is admitted as NON_UI through MANAGE_ONGOING_CALLS, independently of
        // the separate COMPANION list. Confirmed in Android 12L, 13, and current Android 17.
        effective == CallAccess.COMPANION && !companionAppOp -> "Companion call setup incomplete"
        !callingPermissions -> "Calling permissions are missing; re-run setup"
        else -> null
    }
    val ready: Boolean get() = problem == null
}
