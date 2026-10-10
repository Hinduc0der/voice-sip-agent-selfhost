package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.Call
import android.telecom.InCallService

/** Telecom-bound companion; forwards everything to the [CellularEndpoint]. */
class GatewayInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CellularEndpoint.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        CellularEndpoint.current?.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        CellularEndpoint.current?.onCallRemoved(call)
    }

    override fun onDestroy() {
        CellularEndpoint.detachService(this)
        super.onDestroy()
    }
}
