package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import io.github.sparkcyf.voicesipagent.gateway.BridgeSession
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.EchoEndpoint
import io.github.sparkcyf.voicesipagent.gateway.GatewayConfig
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipConfig
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipEndpoint
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipLog
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.sip.SipAccount
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.sip.SipLog
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.redactNumber
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
    val takeover: Boolean = true,
    /** Takeover was switched off during a call; it takes effect when that call ends. */
    val takeoverOffPending: Boolean = false,
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
 * With takeover off, the InCallService is disabled, the companion override withdrawn and SIP
 * unregistered, so calls behave as if the app were not installed.
 */
class GatewayRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-runtime").apply { isDaemon = true } }
    val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settingsFlow: StateFlow<GatewaySettings> = _settings
    val settings: GatewaySettings get() = _settings.value

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    val cellular = CellularEndpoint(app, PrivilegedAudioOpener(shizuku, { settings.audioMethod }, { settings.muteEarpiece })) {
        AppLog.i(it)
    }
    /** [SipUserAgent] or [PjsipEndpoint], per [GatewaySettings.sipStack]. */
    private var sip: CallEndpoint? = null
    private var bridge: CallBridge? = null
    private var started = false

    private val sipLog = object : SipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }
    private val pjLog = object : PjsipLog {
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
        CellularEndpoint.setCompanionComponentEnabled(app, settings.takeover)
        _health.update { it.copy(takeover = settings.takeover) }
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
            val b = svc.setup(app.packageName, settings.takeover)
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
            if (settings.takeover && !_health.value.privilegedReady && _health.value.session == null) {
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
        when {
            !settings.takeover -> cellular.setReadiness(false, "takeover off")
            h.privilegedReady -> cellular.setReadiness(true, "companion ready")
            else -> cellular.setReadiness(false, "privileged setup incomplete")
        }
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    // ------------------------------------------------------------ SIP

    private fun startSip() {
        stopSip()
        val s = settings
        if (!s.takeover) {
            _health.update { it.copy(sip = EndpointStatus(false, "takeover off")) }
            return
        }
        if (!s.sipConfigured) {
            _health.update { it.copy(sip = EndpointStatus(false, "not configured")) }
            return
        }
        val transport = SipTransport.of(s.sipTransport)
        val srtp = SrtpPolicy.of(s.srtp)
        if (srtp == null) {
            AppLog.w("unknown SRTP policy '${s.srtp}'")
            _health.update { it.copy(sip = EndpointStatus(false, "unknown srtp ${s.srtp}")) }
            return
        }
        PjsipEndpoint.setVerboseLogging(s.sipDebugLog)
        if (transport == null || (s.sipStack == SipStack.BUILTIN && transport != SipTransport.UDP)) {
            AppLog.w("SIP transport '${s.sipTransport}' not supported by the ${s.sipStack.id} stack")
            _health.update { it.copy(sip = EndpointStatus(false, "transport ${s.sipTransport} unsupported by ${s.sipStack.id}")) }
            return
        }
        try {
            val ep: CallEndpoint = when (s.sipStack) {
                SipStack.BUILTIN -> SipUserAgent(
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
                ).also { ua ->
                    ua.addStatusListener { _, st -> _health.update { it.copy(sip = st) } }
                    ua.start()
                }
                SipStack.PJSUA2 -> PjsipEndpoint(
                    PjsipConfig(
                        server = s.server,
                        port = s.port,
                        transport = transport,
                        srtp = srtp,
                        username = s.username,
                        password = s.password,
                        domain = s.domain.ifBlank { s.server },
                        displayName = "gateway",
                        localPort = s.localSipPort,
                    ),
                    pjLog,
                ).also { pj ->
                    pj.addStatusListener { _, st -> _health.update { it.copy(sip = st) } }
                    pj.start()
                }
            }
            sip = ep
            AppLog.i("SIP stack: ${s.sipStack.id}")
        } catch (e: Throwable) {
            AppLog.w("SIP start failed", e)
            _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${e.message}")) }
        }
    }

    private fun stopSip() {
        when (val s = sip) {
            is SipUserAgent -> s.stop()
            is PjsipEndpoint -> {
                // Keep the SIP socket only if pjsua2 is about to take it over again.
                val n = settings
                s.stop(
                    releaseTransport = !(n.takeover && n.sipStack == SipStack.PJSUA2 && n.localSipPort == s.config.localPort &&
                        SipTransport.of(n.sipTransport) == s.config.transport),
                )
            }
        }
        sip = null
    }

    private fun refreshSipRegistration() {
        when (val s = sip) {
            is SipUserAgent -> s.refreshRegistration()
            is PjsipEndpoint -> s.refreshRegistration()
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
                        worker.execute(::refreshSipRegistration)
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
        if (!s.takeover) {
            bridge = null
            AppLog.i("bridge idle: takeover off")
            return
        }
        val loopback = _health.value.loopbackTest
        val sideB: CallEndpoint? = if (loopback) EchoEndpoint() else sip
        if (sideB == null) {
            bridge = null
            AppLog.i("bridge idle: SIP not configured")
            return
        }
        val inboundTarget = s.inboundTarget.ifBlank { s.username }
        val b = CallBridge(
            sideA = cellular,
            sideB = sideB,
            routeAtoB = { inboundTarget },
            routeBtoA = { leg -> leg.target?.takeIf { it != s.username } },
            config = GatewayConfig(
                cellularInboundTarget = inboundTarget,
                busyPolicy = s.busyPolicy,
                allowedDialPrefixes = s.allowedPrefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            dialPolicyForA = { n -> if (cellular.isEmergencyNumber(n)) HangupCause.FORBIDDEN else null },
            log = { AppLog.i("bridge: $it") },
        )
        b.addListener { session ->
            _health.update { it.copy(session = describe(session)) }
            if (session.endedAtMs != null) worker.execute { if (_health.value.takeoverOffPending) applyTakeover() }
        }
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
        _settings.value = new
        AppLog.i("settings saved ($new)")
        startSip()
        rebuildBridge()
    }

    /** pjsip info-level logging; takes effect immediately, persisted for the next start. */
    fun setSipDebugLog(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(sipDebugLog = on).also { _settings.value = it })
        PjsipEndpoint.setVerboseLogging(on)
        AppLog.i("SIP debug log ${if (on) "on" else "off"}")
    }

    /** Read at each call's audio setup, so no restart is needed. */
    fun setMuteEarpiece(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(muteEarpiece = on).also { _settings.value = it })
        AppLog.i("earpiece mute ${if (on) "on" else "off"} (from next call)")
    }

    /** The master switch. Switching off mid-call waits for that call to end. */
    fun setTakeover(on: Boolean) = worker.execute {
        if (settings.takeover != on) {
            settingsStore.save(settings.copy(takeover = on).also { _settings.value = it })
        }
        if (!on && _health.value.session != null) {
            _health.update { it.copy(takeoverOffPending = true) }
            AppLog.i("call takeover OFF requested; takes effect after the current call")
            return@execute
        }
        applyTakeover()
    }

    private fun applyTakeover() {
        val on = settings.takeover
        _health.update { it.copy(takeover = on, takeoverOffPending = false) }
        if (on) CellularEndpoint.setCompanionComponentEnabled(app, true)
        startSip()
        rebuildBridge()
        // Withdraws or restores the Telecom companion override.
        runSetup()
        if (!on) CellularEndpoint.setCompanionComponentEnabled(app, false)
        AppLog.i(if (on) "call takeover ON" else "call takeover OFF: all calls are left to the phone")
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
            appendLine("takeover=${h.takeover} pendingOff=${h.takeoverOffPending} muteEarpiece=${settings.muteEarpiece}")
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready} stack=${settings.sipStack.id} " +
                "transport=${settings.sipTransport} srtp=${settings.srtp} debugLog=${settings.sipDebugLog}")
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
