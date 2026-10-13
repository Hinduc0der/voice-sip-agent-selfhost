package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.os.Build
import android.telecom.TelecomManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.Manifest
import androidx.core.content.ContextCompat
import io.github.sparkcyf.voicesipagent.gateway.BridgeSession
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.EchoEndpoint
import io.github.sparkcyf.voicesipagent.gateway.GatewayConfig
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.CallAccessRequirements
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
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
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
    val inCallServiceBound: Boolean = false,
    val loopbackTest: Boolean = false,
    val session: String? = null,
    val lastSetupLog: String = "",
    val holdsDialer: Boolean = false,
    val dialerRoleAvailable: Boolean = false,
    val effectiveCallAccess: Int = CallAccess.AUTO,
    val callAccessReady: Boolean = false,
    val audioReady: Boolean = false,
    val cellularReady: Boolean = false,
    val readinessDetail: String = "Checking gateway setup",
    val calls: CallSnapshot = CallSnapshot(),
    val settingsPending: Boolean = false,
) {
    val privilegedReady: Boolean
        get() = callAccessReady && audioReady && shizuku == ShizukuConnector.State.CONNECTED

    /** Standby needs persistence; inactive companion/dialer configurations do not. */
    val needsForegroundService: Boolean
        get() = session != null || calls.owned > 0 || when (effectiveCallAccess) {
            CallAccess.COMPANION -> takeover
            CallAccess.DIALER -> holdsDialer
            else -> false
        }
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

    // Service entry points can run before the worker initializes. Use the actual saved switch and
    // role immediately so opening the app or receiving BOOT_COMPLETED cannot flash an idle notification.
    private val _health = MutableStateFlow(run {
        val holds = holdsDialer()
        HealthState(takeover = settings.takeover, holdsDialer = holds,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds))
    })
    val health: StateFlow<HealthState> = _health

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    private fun audioFormat() = if (settings.sipStack == SipStack.PJSUA2)
        PcmFormat.WIDEBAND else PcmFormat.NARROWBAND

    val cellular = CellularEndpoint(app,
        PrivilegedAudioOpener(shizuku, { settings.audioMethod }, { settings.muteEarpiece }, ::audioFormat),
        logger = { AppLog.i(it) },
    )
    /** [SipUserAgent] or [PjsipEndpoint], per [GatewaySettings.sipStack]. */
    private var sip: CallEndpoint? = null
    private var bridge: CallBridge? = null
    private var started = false
    private var pendingSettings: GatewaySettings? = null
    private var restartPending = false
    private var loopbackPending: Boolean? = null
    private var setupPending = false
    private var foregroundRequested: Boolean? = null

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
        cellular.onCallsChanged = { snapshot ->
            _health.update { it.copy(calls = snapshot, inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
            worker.execute {
                syncForegroundService()
                if (!cellular.hasCalls && _health.value.session == null) applyDeferredChanges()
            }
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refreshCallAccess() }
        }, IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        AppLog.i("runtime starting ($settings)")
        shizuku.addListener { state, svc ->
            _health.update { it.copy(shizuku = state, shizukuDetail = shizuku.detail) }
            if (state == ShizukuConnector.State.CONNECTED && svc != null) worker.execute { runSetup() }
            if (state != ShizukuConnector.State.CONNECTED) worker.execute { updatePrivileged(null, shizuku.detail) }
        }
        shizuku.start()
        configureCallAccess()
        _health.update { it.copy(takeover = settings.takeover) }
        startSip()
        rebuildBridge()
        updateReadiness()
        watchNetwork()
        // Periodic health: Telecom restarts drop the companion override, Shizuku can die, etc.
        worker.scheduleWithFixedDelay(::periodicCheck, 60, 60, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ privileged setup / health

    fun runSetup() = worker.execute {
        if (hasActiveWork()) {
            setupPending = true
            _health.update { it.copy(settingsPending = true) }
            return@execute
        }
        setupPending = false
        val svc = shizuku.service ?: return@execute updatePrivileged(null, "Shizuku not connected")
        try {
            refreshRoleState()
            val b = svc.setupCallAccess(app.packageName, _health.value.takeover, _health.value.effectiveCallAccess)
            _health.update { it.copy(lastSetupLog = b.getString("setup_log").orEmpty()) }
            applyHealthBundle(b)
        } catch (e: Exception) {
            AppLog.w("privileged setup failed", e)
            updatePrivileged(null, "setup failed: ${e.message}")
        }
    }

    private fun periodicCheck() {
        refreshRoleState()
        val svc = shizuku.service ?: return updateReadiness()
        try {
            val b = svc.health(app.packageName)
            applyHealthBundle(b)
            if (_health.value.takeover && _health.value.effectiveCallAccess == CallAccess.COMPANION &&
                !_health.value.callAccessReady && !hasActiveWork()) {
                AppLog.i("health degraded; re-running setup")
                runSetup()
            }
        } catch (e: Exception) {
            AppLog.w("health check failed", e)
        }
        _health.update { it.copy(inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
    }

    private fun applyHealthBundle(b: Bundle) {
        val map = HealthKeys.BOOLEAN_KEYS.associateWith { b.getBoolean(it) }
        updatePrivileged(map, b.getString(HealthKeys.AUDIO_DETAIL).orEmpty())
    }

    private fun updatePrivileged(map: Map<String, Boolean>?, detail: String) {
        _health.update { it.copy(privileged = map, privilegedDetail = detail) }
        updateReadiness()
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    private fun holdsDialer() = app.getSystemService(TelecomManager::class.java).defaultDialerPackage == app.packageName

    private fun refreshRoleState() {
        val holds = holdsDialer()
        val available = app.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_DIALER)
        _health.update { it.copy(holdsDialer = holds,
            dialerRoleAvailable = available,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds)) }
    }

    private fun configureCallAccess() {
        refreshRoleState()
        cellular.setPreferredDialerService(_health.value.holdsDialer)
        if (!cellular.hasCalls) CellularEndpoint.setCompanionComponentEnabled(app,
            _health.value.takeover && _health.value.effectiveCallAccess == CallAccess.COMPANION && Build.VERSION.SDK_INT >= 31)
        // The dialer activity/UI service remain enabled, including when takeover is off.
    }

    fun refreshCallAccess() = worker.execute {
        configureCallAccess()
        updateReadiness()
        // Changing companion registration can change binding. Defer app-controlled setup while
        // Telecom has any call, including calls outside the bridge.
        if (!hasActiveWork()) runSetup() else setupPending = true
    }

    @Suppress("DEPRECATION")
    private fun componentEnabled(type: Class<*>, service: Boolean): Boolean = runCatching {
        val component = ComponentName(app, type)
        val pm = app.packageManager
        val info = if (service) pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            else pm.getActivityInfo(component, 0)
        val setting = pm.getComponentEnabledSetting(component)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        enabled && info.applicationInfo.enabled && (!service ||
            (info as android.content.pm.ServiceInfo).permission == Manifest.permission.BIND_INCALL_SERVICE)
    }.getOrDefault(false)

    private fun updateReadiness() {
        refreshRoleState()
        val h = _health.value
        val p = h.privileged
        val requirements = CallAccessRequirements(h.effectiveCallAccess, Build.VERSION.SDK_INT,
            h.holdsDialer, h.dialerRoleAvailable,
            componentEnabled(GatewayDialerActivity::class.java, false) && componentEnabled(GatewayDialerService::class.java, true),
            componentEnabled(GatewayInCallService::class.java, true),
            p?.get(HealthKeys.MANAGE_ONGOING_CALLS) == true, p?.get(HealthKeys.COMPANION_OVERRIDE) == true,
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).all {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            })
        val access = requirements.ready
        val audio = when (settings.audioMethod) {
            AudioMethod.OFFICIAL -> p?.get(HealthKeys.REDIRECT_AUDIO) == true
            AudioMethod.LEGACY -> p?.get(HealthKeys.DIRECT_AUDIO) == true
            else -> p?.get(HealthKeys.DIRECT_AUDIO) == true || p?.get(HealthKeys.REDIRECT_AUDIO) == true
        }
        val detail = when {
            !h.takeover -> if (h.holdsDialer) "Gateway off; switch dialer to handle calls normally" else "Gateway off"
            !access -> requirements.problem!!
            h.shizuku != ShizukuConnector.State.CONNECTED -> "Shizuku not connected"
            !audio -> "Selected call audio unavailable: ${h.privilegedDetail}"
            !h.loopbackTest && !h.sip.ready -> "SIP unavailable: ${h.sip.detail}"
            else -> "Gateway ready"
        }
        val ready = h.takeover && access && audio && h.shizuku == ShizukuConnector.State.CONNECTED &&
            (h.loopbackTest || h.sip.ready)
        _health.update { it.copy(callAccessReady = access, audioReady = audio, cellularReady = ready, readinessDetail = detail) }
        cellular.setReadiness(ready, detail)
        syncForegroundService()
    }

    private fun syncForegroundService() {
        val h = _health.value
        if (foregroundRequested == h.needsForegroundService) return
        if (GatewayService.sync(app, h)) foregroundRequested = h.needsForegroundService
    }

    private fun hasActiveWork() = cellular.hasCalls || _health.value.session != null

    private fun sipStatus(endpoint: CallEndpoint, status: EndpointStatus) = worker.execute {
        if (sip !== endpoint) return@execute
        _health.update { it.copy(sip = status) }
        updateReadiness()
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
                    ua.addStatusListener { ep, st -> sipStatus(ep, st) }
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
                    pj.addStatusListener { ep, st -> sipStatus(ep, st) }
                    pj.start()
                }
            }
            sip = ep
            _health.update { it.copy(sip = ep.status) }
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
        val sideB: CallEndpoint? = if (loopback) EchoEndpoint(format = audioFormat()) else sip
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
            worker.execute {
                syncForegroundService()
                if (session.endedAtMs != null && !cellular.hasCalls) applyDeferredChanges()
            }
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
        require(new.audioMethod in 0..2 && new.callAccess in 0..2) { "unknown platform setting" }
        if (hasActiveWork()) {
            pendingSettings = new
            _health.update { it.copy(settingsPending = true) }
            AppLog.i("settings will apply after current calls end")
            return@execute
        }
        val old = settings
        settingsStore.save(new)
        _settings.value = new
        AppLog.i("settings saved ($new)")
        val platformOnly = old.copy(audioMethod = new.audioMethod, callAccess = new.callAccess,
            muteEarpiece = new.muteEarpiece, sipDebugLog = new.sipDebugLog) == new
        PjsipEndpoint.setVerboseLogging(new.sipDebugLog)
        if (!platformOnly) { startSip(); rebuildBridge() }
        configureCallAccess()
        runSetup()
        updateReadiness()
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
        if (!on && hasActiveWork()) {
            _health.update { it.copy(takeoverOffPending = true) }
            AppLog.i("call takeover OFF requested; takes effect after the current call")
            return@execute
        }
        applyTakeover()
    }

    private fun applyTakeover() {
        val on = settings.takeover
        _health.update { it.copy(takeover = on, takeoverOffPending = false) }
        configureCallAccess()
        startSip()
        rebuildBridge()
        // Withdraws or restores the Telecom companion override.
        runSetup()
        updateReadiness()
        AppLog.i(if (on) "call takeover ON" else "call takeover OFF: all calls are left to the phone")
    }

    fun setLoopbackTest(on: Boolean) = worker.execute {
        if (hasActiveWork()) { loopbackPending = on; _health.update { it.copy(settingsPending = true) }; return@execute }
        if (_health.value.loopbackTest == on) return@execute
        _health.update { it.copy(loopbackTest = on) }
        AppLog.i("loopback test ${if (on) "ON: inbound cellular calls are answered and echoed" else "OFF"}")
        rebuildBridge()
        updateReadiness()
    }

    fun restartSip() = worker.execute {
        if (hasActiveWork()) { restartPending = true; _health.update { it.copy(settingsPending = true) }; return@execute }
        startSip()
        rebuildBridge()
        updateReadiness()
    }

    private fun applyDeferredChanges() {
        if (hasActiveWork()) return
        val queued = pendingSettings
        pendingSettings = null
        val restart = restartPending
        restartPending = false
        val loopback = loopbackPending
        loopbackPending = null
        _health.update { it.copy(settingsPending = false) }
        if (_health.value.takeoverOffPending) applyTakeover()
        if (queued != null) applySettings(queued.copy(takeover = settings.takeover))
        if (loopback != null) setLoopbackTest(loopback)
        if (restart) restartSip()
        configureCallAccess()
        updateReadiness()
        if (setupPending || queued != null || _health.value.privileged == null ||
            (_health.value.effectiveCallAccess == CallAccess.COMPANION && !_health.value.callAccessReady)) runSetup()
    }

    fun statusReport(): String {
        val h = _health.value
        return buildString {
            appendLine("takeover=${h.takeover} pendingOff=${h.takeoverOffPending} muteEarpiece=${settings.muteEarpiece}")
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready} stack=${settings.sipStack.id} " +
                "transport=${settings.sipTransport} srtp=${settings.srtp} debugLog=${settings.sipDebugLog}")
            appendLine("cellular=${cellular.status.detail} inCallServiceBound=${CellularEndpoint.isInCallServiceBound} " +
                "service=${CellularEndpoint.boundServiceDescription ?: "none"}")
            appendLine("sdk=${Build.VERSION.SDK_INT} callAccess=${settings.callAccess} effective=${h.effectiveCallAccess} " +
                "defaultDialer=${h.holdsDialer} audioMethod=${settings.audioMethod} audioReady=${h.audioReady}")
            appendLine("calls=${h.calls.summary} settingsPending=${h.settingsPending}")
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
