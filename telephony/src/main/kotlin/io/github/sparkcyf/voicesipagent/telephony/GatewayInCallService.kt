package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.Call
import android.telecom.InCallService

/** Shared Telecom callbacks; this component is a companion and its dialer subclass provides UI. */
open class GatewayInCallService : InCallService() {
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

    override fun onDestroy() {
        try { CellularEndpoint.detachManagedService(this) } finally { super.onDestroy() }
    }
}
