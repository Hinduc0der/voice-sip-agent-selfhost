package com.voicesipagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
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
import com.voicesipagent.privileged.HealthKeys
import com.voicesipagent.privileged.ShizukuConnector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { pad -> Screen(runtime, Modifier.padding(pad)) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier) {
    val h by rt.health.collectAsState()
    val log by AppLog.tail.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
        val cur by rt.settingsFlow.collectAsState()
        Section("Takeover") {
            Toggle("Take over calls", cur.takeover, { rt.setTakeover(it) }) {
                when {
                    h.takeoverOffPending -> "Turning off after the current call"
                    cur.takeover -> "Calls are bridged to the PBX"
                    else -> "Off: the phone behaves as if this app were not installed"
                }
            }
            Toggle("Silence handset during bridged calls", cur.muteEarpiece, { rt.setMuteEarpiece(it) }) { "Earpiece and microphone stay off while the PBX side is connected" }
        }
        Section("Health") {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check("Ongoing-call access (appop)", p?.get(HealthKeys.MANAGE_ONGOING_CALLS) == true)
            Check("Telecom companion override", p?.get(HealthKeys.COMPANION_OVERRIDE) == true)
            Check("Call audio interceptable", p?.get(HealthKeys.INTERCEPTABLE) == true)
            Check("Doze allowlisted", p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check("SIP registration", h.sip.ready, h.sip.detail)
            if (h.privilegedDetail.isNotBlank()) Text(h.privilegedDetail, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }) { Text("Grant Shizuku") }
                }
                OutlinedButton(onClick = { rt.runSetup() }) { Text("Re-run setup") }
                OutlinedButton(onClick = { rt.restartSip() }) { Text("Re-register") }
            }
        }
        Section("Call") {
            Text(h.session ?: "No active session")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loopback test (echo inbound calls)", Modifier.weight(1f))
                Switch(checked = h.loopbackTest, onCheckedChange = { rt.setLoopbackTest(it) })
            }
        }
        SettingsSection(rt)
        Section("Log") {
            Text(
                log.takeLast(60).reversed().joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
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
    Section("Settings") {
        Field("PBX host", server) { server = it }
        Field("PBX port", port, KeyboardType.Number) { port = it }
        Field("Extension / user", user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field("Cellular inbound -> SIP target", target) { target = it }
        Field("Allowed dial-out prefixes (comma separated, empty = any)", prefixes) { prefixes = it }
        Button(onClick = {
            rt.applySettings(
                cur.copy(
                    server = server, port = port.toIntOrNull() ?: 5060, username = user, password = password,
                    inboundTarget = target, allowedPrefixes = prefixes,
                ),
            )
        }) { Text("Save & apply") }
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
