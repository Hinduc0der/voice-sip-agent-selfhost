package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

class MainActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val log by AppLog.tail.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
        val cur by rt.settingsFlow.collectAsState()
        Section("Takeover") {
            Toggle("Take over calls", cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> "Turning off after the current call"
                    h.takeoverOnPending -> "Turning on after current calls end"
                    h.dialerChangeRunning -> "Changing the default dialer"
                    h.dialerChangePending -> if (h.dialerChangeAcquire) "Waiting to become the default dialer" else "Waiting to restore your previous dialer"
                    cur.takeover -> if (h.cellularReady) "Calls are bridged to the PBX" else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> "Gateway off; keeping the default-dialer role"
                    h.holdsDialer -> "Gateway off; dialer restoration needs attention"
                    else -> "Gateway off; calls stay with your dialer"
                }
            }
            if (h.effectiveCallAccess == CallAccess.DIALER) {
                Text("Turning on requests the default-dialer role. Turning off restores your previous dialer unless you keep the role in Advanced settings.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (h.settingsPending) Text("Settings will apply after current calls end")
        }
        Section("Health") {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check("Call access", h.callAccessReady,
                if (h.effectiveCallAccess == CallAccess.DIALER) "Default dialer" else "Alongside your dialer")
            Check("Call audio access", h.audioReady)
            Check("Doze allowlisted", p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check("SIP registration", h.sip.ready, h.sip.detail)
            Text(h.readinessDetail, style = MaterialTheme.typography.bodySmall)
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                Text("Default-dialer mode bridges calls through the PBX. For manual calling, switch back to your usual dialer.")
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text("Enable takeover") }
                }
                if (!h.dialerRoleAvailable) Text("Default dialer selection is unavailable on this device.")
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.dialerChangeFailed || (h.holdsDialer && !cur.takeover && !cur.keepDefaultDialer)) {
                if (h.holdsDialer) OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text("Restore previous dialer") }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text("Choose dialer in Settings") }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text("Cancel dialer change") }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }) { Text("Grant Shizuku") }
                }
                OutlinedButton(onClick = { rt.runSetup() }) { Text("Re-run setup") }
                OutlinedButton(onClick = { rt.restartSip() }) { Text("Re-register") }
            }
        }
        Section("Call") {
            Text(h.calls.summary)
            Text(h.session ?: "No active session")
        }
        SettingsSection(rt)
        Section("Log") {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
        var advanced by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
        if (advanced) {
            Section("Platform integration") {
                Choice("Call access", listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), cur.callAccess,
                    { when (it) { CallAccess.AUTO -> "Automatic"; CallAccess.COMPANION -> "Alongside dialer"; else -> "Default dialer" } }) {
                    rt.applySettings(cur.copy(callAccess = it))
                }
                Toggle("Keep default-dialer role when gateway is off", cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }) {
                    "Applies the next time takeover is switched off. Restore manually below when needed."
                }
                if (h.holdsDialer) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text("Restore previous dialer") }
                } else if (h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = !h.dialerChangeRunning) { Text("Become default dialer") }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text("Choose dialer in Settings") }
                Text("Manual dialer selection leaves takeover unchanged. Selecting Default dialer above applies to the next switch-on.",
                    style = MaterialTheme.typography.bodySmall)
                Choice("Cellular audio", listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), cur.audioMethod,
                    { when (it) { AudioMethod.AUTO -> "Automatic"; AudioMethod.LEGACY -> "Direct telephony"; else -> "Call redirection" } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }) {
                    rt.applySettings(cur.copy(audioMethod = it))
                }
                Toggle("Mute local call output", cur.muteEarpiece, { rt.setMuteEarpiece(it) }) {
                    val backendAvailable = when (cur.audioMethod) {
                        AudioMethod.LEGACY -> h.privileged?.get(HealthKeys.DIRECT_AUDIO) == true
                        AudioMethod.OFFICIAL -> h.privileged?.get(HealthKeys.REDIRECT_AUDIO) == true
                        else -> true
                    }
                    if (!cur.muteEarpiece)
                        "Keeps normal call output for local listening; microphone stays muted. Applies from next call."
                    else if (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) == true && backendAvailable)
                        "Mutes call output when supported; applies from next call"
                    else "Unavailable with the current platform or audio method"
                }
                Toggle("Loopback test (echo inbound calls)", h.loopbackTest, { rt.setLoopbackTest(it) }) { "Echoes incoming calls instead of connecting the PBX" }
                Toggle("pjsip debug log", cur.sipDebugLog, { rt.setSipDebugLog(it) }) { "Info-level logs with masked numbers" }
            }
            Section("Diagnostics") {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "Waiting for helper")
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text("Audio access and frame counters do not verify remote delivery. Verify both directions on this device.")
            }
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime) {
    // Re-seed the form whenever settings change elsewhere (e.g. adb provisioning).
    val cur by rt.settingsFlow.collectAsState()
    var server by remember(cur) { mutableStateOf(cur.server) }
    var port by remember(cur) { mutableStateOf(cur.port.toString()) }
    var user by remember(cur) { mutableStateOf(cur.username) }
    var password by remember(cur) { mutableStateOf(cur.password) }
    var target by remember(cur) { mutableStateOf(cur.inboundTarget) }
    var prefixes by remember(cur) { mutableStateOf(cur.allowedPrefixes) }
    var domain by remember(cur) { mutableStateOf(cur.domain) }
    var stack by remember(cur) { mutableStateOf(cur.sipStack) }
    var transport by remember(cur) { mutableStateOf(SipTransport.of(cur.sipTransport) ?: SipTransport.UDP) }
    var srtp by remember(cur) { mutableStateOf(SrtpPolicy.of(cur.srtp) ?: SrtpPolicy.OPTIONAL) }
    val builtin = stack == SipStack.BUILTIN
    Section("Settings") {
        Choice("SIP stack", SipStack.entries, stack, { it.id }) {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) transport = SipTransport.UDP
        }
        Choice("Transport", SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }) {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice("SRTP (TLS only)", SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }) {
            srtp = it
        }
        Field("PBX host", server) { server = it }
        Field("PBX port", port, KeyboardType.Number) { port = it }
        Field("SIP domain (empty = PBX host)", domain) { domain = it }
        Field("Extension / user", user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field("Cellular inbound -> SIP target (blank = username)", target) { target = it }
        Field("Allowed dial-out prefixes (comma separated, empty = any)", prefixes) { prefixes = it }
        Button(onClick = {
            rt.applySettings(
                cur.copy(
                    server = server, port = port.toIntOrNull() ?: transport.defaultPort, username = user, password = password,
                    inboundTarget = target, allowedPrefixes = prefixes, domain = domain,
                    sipStack = stack, sipTransport = transport.id, srtp = srtp.id,
                ),
            )
        }) { Text("Save & apply") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail(), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
