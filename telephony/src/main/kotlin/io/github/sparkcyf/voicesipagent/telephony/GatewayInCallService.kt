package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.Call
import android.telecom.InCallService

/** Telecom-bound companion; forwards everything to the [CellularEndpoint]. */
open class GatewayInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CellularEndpoint.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        CellularEndpoint.current?.onCallAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        CellularEndpoint.current?.onCallRemoved(this, call)
    }

    override fun onDestroy() {
        try { CellularEndpoint.detachService(this) } finally { super.onDestroy() }
    }
}
