package com.voicesipagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import com.voicesipagent.gateway.BridgeSession
import com.voicesipagent.gateway.CallBridge
import com.voicesipagent.gateway.EchoEndpoint
import com.voicesipagent.gateway.GatewayConfig
import com.voicesipagent.privileged.HealthKeys
import com.voicesipagent.privileged.ShizukuConnector
import com.voicesipagent.sip.SipAccount
import com.voicesipagent.sip.SipConfig
import com.voicesipagent.sip.SipLog
import com.voicesipagent.sip.SipUserAgent
import com.voicesipagent.telephony.CellularEndpoint
import com.voicesipagent.voice.CallEndpoint
import com.voicesipagent.voice.EndpointStatus
import com.voicesipagent.voice.HangupCause
import com.voicesipagent.voice.LegState
import com.voicesipagent.voice.redactNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class HealthState(
    val shizuku: ShizukuConnector.State = ShizukuConnector.State.SHIZUKU_NOT_RUNNING,
    val shizukuDetail: String = "",
    /** Null until the privileged service has reported. */
    val privileged: Map<String, Boolean>? = null,
    val privilegedDetail: String = "",
    val sip: EndpointStatus = EndpointStatus(false, "not configured"),
    val companionBound: Boolean = false,
    val loopbackTest: Boolean = false,
    val session: String? = null,
    val lastSetupLog: String = "",
) {
    val privilegedReady: Boolean
        get() = privileged?.let { it[HealthKeys.MANAGE_ONGOING_CALLS] == true && it[HealthKeys.COMPANION_OVERRIDE] == true } == true
}

/**
 * Owns every long-lived component and wires them together:
 * Shizuku -> privileged setup -> cellular endpoint readiness; settings -> SIP UA; both -> CallBridge.
 * The bridge's B side is the SIP UA normally, or [EchoEndpoint] in loopback-test mode.
 */
class GatewayRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-runtime").apply { isDaemon = true } }
    val settingsStore = SettingsStore(app)
    @Volatile var settings: GatewaySettings = settingsStore.load(); private set

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    val cellular = CellularEndpoint(app, PrivilegedAudioOpener(shizuku) { settings.audioMethod }) { AppLog.i(it) }
    private var sip: SipUserAgent? = null
    private var bridge: CallBridge? = null
    private var started = false

    private val sipLog = object : SipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }

    fun start() = worker.execute {
        if (started) return@execute
        started = true
        AppLog.i("runtime starting ($settings)")
        shizuku.addListener { state, svc ->
            _health.update { it.copy(shizuku = state, shizukuDetail = shizuku.detail) }
            if (state == ShizukuConnector.State.CONNECTED && svc != null) worker.execute { runSetup() }
            if (state != ShizukuConnector.State.CONNECTED) updatePrivileged(null, shizuku.detail)
        }
        shizuku.start()
        startSip()
        rebuildBridge()
        watchNetwork()
        // Periodic health: Telecom restarts drop the companion override, Shizuku can die, etc.
        worker.scheduleWithFixedDelay(::periodicCheck, 60, 60, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ privileged setup / health

    fun runSetup() = worker.execute {
        val svc = shizuku.service ?: return@execute updatePrivileged(null, "Shizuku not connected")
        try {
            val b = svc.setup(app.packageName)
            _health.update { it.copy(lastSetupLog = b.getString("setup_log").orEmpty()) }
            applyHealthBundle(b)
        } catch (e: Exception) {
            AppLog.w("privileged setup failed", e)
            updatePrivileged(null, "setup failed: ${e.message}")
        }
    }

    private fun periodicCheck() {
        val svc = shizuku.service ?: return
        try {
            val b = svc.health(app.packageName)
            applyHealthBundle(b)
            if (!_health.value.privilegedReady && _health.value.session == null) {
                AppLog.i("health degraded; re-running setup")
                runSetup()
            }
        } catch (e: Exception) {
            AppLog.w("health check failed", e)
        }
        _health.update { it.copy(companionBound = CellularEndpoint.isCompanionBound) }
    }

    private fun applyHealthBundle(b: Bundle) {
        val map = HealthKeys.BOOLEAN_KEYS.associateWith { b.getBoolean(it) }
        updatePrivileged(map, b.getString(HealthKeys.DETAIL).orEmpty())
    }

    private fun updatePrivileged(map: Map<String, Boolean>?, detail: String) {
        _health.update { it.copy(privileged = map, privilegedDetail = detail) }
        val h = _health.value
        cellular.setReadiness(h.privilegedReady, if (h.privilegedReady) "companion ready" else "privileged setup incomplete")
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    // ------------------------------------------------------------ SIP

    private fun startSip() {
        sip?.stop()
        sip = null
        val s = settings
        if (!s.sipConfigured) {
            _health.update { it.copy(sip = EndpointStatus(false, "not configured")) }
            return
        }
        val ua = SipUserAgent(
            SipConfig(
                account = SipAccount(
                    server = s.server,
                    port = s.port,
                    username = s.username,
                    password = s.password,
                    domain = s.domain.ifBlank { s.server },
                    displayName = "gateway",
                ),
                localPort = s.localSipPort,
            ),
            sipLog,
        )
        ua.addStatusListener { _, st -> _health.update { it.copy(sip = st) } }
        try {
            ua.start()
            sip = ua
        } catch (e: Exception) {
            AppLog.w("SIP start failed", e)
            _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${e.message}")) }
        }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var lastAddrs: String? = null
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress.orEmpty() }
                if (addrs != lastAddrs) {
                    if (lastAddrs != null) {
                        AppLog.i("network addresses changed; re-registering")
                        sip?.refreshRegistration()
                    }
                    lastAddrs = addrs
                }
            }
        })
    }

    // ------------------------------------------------------------ bridge

    private fun rebuildBridge() {
        bridge?.stop()
        val s = settings
        val loopback = _health.value.loopbackTest
        val sideB: CallEndpoint? = if (loopback) EchoEndpoint() else sip
        if (sideB == null) {
            bridge = null
            AppLog.i("bridge idle: SIP not configured")
            return
        }
        val b = CallBridge(
            sideA = cellular,
            sideB = sideB,
            routeAtoB = { s.inboundTarget },
            routeBtoA = { leg -> leg.target?.takeIf { it != s.username } },
            config = GatewayConfig(
                cellularInboundTarget = s.inboundTarget,
                busyPolicy = s.busyPolicy,
                allowedDialPrefixes = s.allowedPrefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            dialPolicyForA = { n -> if (cellular.isEmergencyNumber(n)) HangupCause.FORBIDDEN else null },
            log = { AppLog.i("bridge: $it") },
        )
        b.addListener { session -> _health.update { it.copy(session = describe(session)) } }
        b.start()
        bridge = b
        AppLog.i("bridge ready: cellular <-> ${sideB.name}")
    }

    private fun describe(s: BridgeSession): String? {
        if (s.endedAtMs != null) return null
        val out = s.outbound
        return "${s.origin}: ${redactNumber(s.inbound.remote.number)} ${s.inbound.state} -> ${out?.kind} ${out?.state ?: LegState.NEW}"
    }

    // ------------------------------------------------------------ external controls

    fun applySettings(new: GatewaySettings) = worker.execute {
        settingsStore.save(new)
        settings = new
        AppLog.i("settings saved ($new)")
        startSip()
        rebuildBridge()
    }

    fun setLoopbackTest(on: Boolean) = worker.execute {
        if (_health.value.loopbackTest == on) return@execute
        _health.update { it.copy(loopbackTest = on) }
        AppLog.i("loopback test ${if (on) "ON: inbound cellular calls are answered and echoed" else "OFF"}")
        rebuildBridge()
    }

    fun restartSip() = worker.execute {
        startSip()
        rebuildBridge()
    }

    fun statusReport(): String {
        val h = _health.value
        return buildString {
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready}")
            appendLine("cellular=${cellular.status.detail} companionBound=${CellularEndpoint.isCompanionBound}")
            appendLine("loopback=${h.loopbackTest} session=${h.session}")
            shizuku.service?.let { appendLine("tap=${runCatching { it.audioStats() }.getOrDefault("?")}") }
        }
    }

    companion object {
        @Volatile private var instance: GatewayRuntime? = null

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) { instance ?: GatewayRuntime(context).also { instance = it } }
    }
}
