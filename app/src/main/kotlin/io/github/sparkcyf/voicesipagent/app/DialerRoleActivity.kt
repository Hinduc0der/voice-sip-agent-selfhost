package io.github.sparkcyf.voicesipagent.app

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow

/** Role acquisition is confined to explicit UI actions. */
abstract class DialerRoleActivity : ComponentActivity() {
    protected val roleMessage = MutableStateFlow("")
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        roleMessage.value = if (result.resultCode == RESULT_OK) "" else "Default dialer selection was cancelled or declined."
        GatewayRuntime.get(this).refreshCallAccess()
    }

    protected fun requestDialer() {
        afterUnlock {
            val role = getSystemService(RoleManager::class.java)
            when {
                !role.isRoleAvailable(RoleManager.ROLE_DIALER) -> roleMessage.value = "Default dialer selection is unavailable on this device."
                role.isRoleHeld(RoleManager.ROLE_DIALER) -> GatewayRuntime.get(this).refreshCallAccess()
                else -> runCatching { roleRequest.launch(role.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                    .onFailure { roleMessage.value = "Could not open default dialer selection. Use system default-app settings." }
            }
        }
    }

    protected fun switchDialer() = afterUnlock {
        val opened = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }.isSuccess
        if (!opened && runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.isFailure) {
            roleMessage.value = "Open system Settings, then Default apps, and select your usual phone app."
        }
    }

    protected fun openConfiguration() = afterUnlock {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun afterUnlock(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { action(); return }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
            override fun onDismissCancelled() { roleMessage.value = "Unlock the phone to continue." }
            override fun onDismissError() { roleMessage.value = "Unlock the phone to continue." }
        })
    }

    override fun onResume() {
        super.onResume()
        GatewayRuntime.get(this).refreshCallAccess()
    }
}
