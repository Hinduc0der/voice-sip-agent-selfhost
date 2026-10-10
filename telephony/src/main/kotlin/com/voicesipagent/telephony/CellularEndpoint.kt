package com.voicesipagent.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.voicesipagent.voice.CallEndpoint
import com.voicesipagent.voice.CallLeg
import com.voicesipagent.voice.EndpointStatus
import com.voicesipagent.voice.EndpointStatusListener
import com.voicesipagent.voice.HangupCause
import com.voicesipagent.voice.IncomingCallHandler
import com.voicesipagent.voice.LegDirection
import com.voicesipagent.voice.Party
import com.voicesipagent.voice.redactNumber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's cellular line as a [CallEndpoint], driven through the companion InCallService.
 *
 * Only calls this endpoint owns are touched: inbound calls offered to the gateway, and outbound
 * calls it placed itself. Calls the user dials by hand, or that arrive while such a call is in
 * progress, are left to the stock phone app.
 */
class CellularEndpoint(
    context: Context,
    internal val audioOpener: CallAudioOpener,
    private val logger: (String) -> Unit,
) : CallEndpoint {
    override val name = "cellular"

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    private val ids = AtomicInteger(1)
    @Volatile private var handler: IncomingCallHandler? = null

    /** Legs we own, keyed by Telecom call. Main thread only. */
    private val legs = HashMap<Call, CellularLeg>()

    /** Outbound placements waiting for Telecom's onCallAdded. Main thread only. */
    private val pending = ArrayList<Pending>()

    private class Pending(val leg: CellularLeg, val digits: String, val atMs: Long)

    @Volatile override var status = EndpointStatus(false, "companion not configured")
        private set

    init {
        current = this
    }

    fun close() {
        if (current === this) current = null
    }

    internal fun log(msg: String) = logger(msg)

    /** Readiness is decided by the privileged health check (companion override etc.). */
    fun setReadiness(ready: Boolean, detail: String) {
        val s = EndpointStatus(ready, detail)
        if (s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
    }

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        this.handler = handler
    }

    override fun addStatusListener(listener: EndpointStatusListener) {
        statusListeners.addIfAbsent(listener)
    }

    override fun removeStatusListener(listener: EndpointStatusListener) {
        statusListeners.remove(listener)
    }

    fun isEmergencyNumber(number: String): Boolean = try {
        telephony.isEmergencyNumber(number)
    } catch (e: Exception) {
        // Fail closed: if we cannot tell, treat it as emergency and refuse to dial.
        true
    }

    override fun dial(target: String, caller: Party): CallLeg {
        val leg = CellularLeg("c${ids.getAndIncrement()}", LegDirection.OUTBOUND, Party(target), target, this, main)
        val digits = target.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        when {
            digits.isEmpty() -> leg.failPlacement(HangupCause.NOT_FOUND)
            isEmergencyNumber(digits) -> {
                log("refusing emergency number from gateway")
                leg.failPlacement(HangupCause.FORBIDDEN)
            }
            app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED -> {
                log("CALL_PHONE not granted")
                leg.failPlacement(HangupCause.INTERNAL_ERROR)
            }
            else -> main.post { place(leg, digits) }
        }
        return leg
    }

    private fun place(leg: CellularLeg, digits: String) {
        if (hasForeignCall()) {
            log("line busy with a non-gateway call")
            leg.failPlacement(HangupCause.BUSY)
            return
        }
        val p = Pending(leg, digits, SystemClock.elapsedRealtime())
        pending += p
        try {
            telecom.placeCall(Uri.fromParts("tel", digits, null), Bundle())
            log("placeCall ${redactNumber(digits)} (leg ${leg.id})")
        } catch (e: Exception) {
            pending -= p
            log("placeCall failed: $e")
            leg.failPlacement(HangupCause.NETWORK_ERROR)
            return
        }
        main.postDelayed({
            if (pending.remove(p)) {
                log("leg ${leg.id}: Telecom never reported the call")
                leg.failPlacement(HangupCause.NETWORK_ERROR)
            }
        }, PLACEMENT_TIMEOUT_MS)
    }

    // ------------------------------------------------------------ InCallService callbacks (main thread)

    internal fun onCallAdded(call: Call) {
        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        when (details.callDirection) {
            Call.Details.DIRECTION_OUTGOING -> {
                val match = pending.firstOrNull { sameNumber(it.digits, number) }
                if (match == null) {
                    log("ignoring user-placed outgoing call")
                    return
                }
                pending -= match
                legs[call] = match.leg
                match.leg.bind(call)
            }
            Call.Details.DIRECTION_INCOMING -> {
                val h = handler
                if (h == null || hasForeignCall(except = call)) {
                    log("leaving incoming call ${redactNumber(number)} to the phone app")
                    return
                }
                val leg = CellularLeg("c${ids.getAndIncrement()}", LegDirection.INBOUND, Party(number.ifEmpty { null }, details.callerDisplayName), null, this, main)
                legs[call] = leg
                leg.bind(call)
                log("incoming cellular call ${redactNumber(number)} -> gateway (leg ${leg.id})")
                h.onIncomingCall(leg)
            }
            else -> log("ignoring call with unknown direction")
        }
    }

    internal fun onCallRemoved(call: Call) {
        legs.remove(call)?.onRemoved()
    }

    internal fun onLegEnded(leg: CellularLeg) {
        legs.entries.removeAll { it.value === leg }
        if (legs.isEmpty()) setMuted(false)
    }

    /** True if Telecom has a live call we do not own (e.g. the user's own conversation). */
    private fun hasForeignCall(except: Call? = null): Boolean {
        val svc = service ?: return false
        return svc.calls.any { it !== except && it !in legs && it.state != Call.STATE_DISCONNECTED }
    }

    internal fun setMuted(muted: Boolean) {
        main.post {
            val svc = service ?: return@post
            if (svc.callAudioState?.isMuted != muted) svc.setMuted(muted)
        }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter(Char::isDigit)
        val db = b.filter(Char::isDigit)
        if (da.isEmpty() || db.isEmpty()) return false
        val n = minOf(da.length, db.length, 8)
        return da.takeLast(n) == db.takeLast(n)
    }

    companion object {
        private const val PLACEMENT_TIMEOUT_MS = 10_000L

        @Volatile internal var current: CellularEndpoint? = null
        @Volatile private var service: GatewayInCallService? = null

        val isCompanionBound: Boolean get() = service != null

        internal fun attachService(s: GatewayInCallService) {
            service = s
            current?.log("companion InCallService bound")
        }

        internal fun detachService(s: GatewayInCallService) {
            if (service === s) service = null
            current?.log("companion InCallService unbound")
        }
    }
}
