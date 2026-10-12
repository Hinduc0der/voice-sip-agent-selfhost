package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Safe to display over the lockscreen: no configuration, caller numbers, or diagnostic logs. */
class GatewayDialerActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        setContent {
            val health by runtime.health.collectAsState()
            val message by roleMessage.collectAsState()
            LaunchedEffect(health.holdsDialer, health.calls) {
                setShowWhenLocked(health.holdsDialer && health.calls.count > 0)
                setTurnScreenOn(health.holdsDialer && health.calls.incoming)
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { inset ->
                    Column(Modifier.fillMaxSize().padding(inset).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
                        Text(health.calls.summary)
                        Text(if (health.session == null) "No gateway session" else "Gateway bridge active")
                        // Avoid putting server names or low-level failure details on a locked screen.
                        Text(when {
                            !health.takeover -> "Gateway is off. Switch to your usual dialer to handle calls."
                            health.cellularReady -> "Gateway is ready to bridge calls through the PBX."
                            else -> "Gateway is unavailable. Open configuration or switch to your usual dialer."
                        })
                        Text("For manual calls, select your usual phone app in system default-app settings.")
                        Button(onClick = ::switchDialer) { Text("Switch dialer") }
                        if (!health.holdsDialer && health.dialerRoleAvailable) {
                            OutlinedButton(onClick = ::requestDialer) { Text("Select as default dialer") }
                        }
                        OutlinedButton(onClick = ::openConfiguration) { Text("Open configuration") }
                        if (message.isNotEmpty()) Text(message)
                    }
                }
            }
        }
    }
}
