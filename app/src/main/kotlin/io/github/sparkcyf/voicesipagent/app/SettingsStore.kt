package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(
    /** Master switch. Off: the app leaves every call alone, exactly as if it were not installed. */
    val takeover: Boolean = true,
    /** Silence the handset earpiece/mic while a call is bridged. */
    val muteEarpiece: Boolean = true,
    val server: String = "",
    val port: Int = 5060,
    /** udp | tcp | tls; the built-in stack only supports udp. */
    val sipTransport: String = "udp",
    /** off | optional | mandatory SDES-SRTP (pjsua2 over TLS only). */
    val srtp: String = "optional",
    /** pjsip info-level logging (development); errors/warnings are always logged. */
    val sipDebugLog: Boolean = false,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val localSipPort: Int = 5062,
    /** SIP extension / DID that receives cellular inbound calls; blank = the SIP username (trunk DID). */
    val inboundTarget: String = "",
    /** Comma-separated; empty = any number may be dialed out. */
    val allowedPrefixes: String = "",
    val busyPolicy: BusyPolicy = BusyPolicy.REJECT,
    val audioMethod: Int = AudioMethod.AUTO,
    val callAccess: Int = CallAccess.AUTO,
    /** Which SIP implementation backs the PBX side. */
    val sipStack: SipStack = SipStack.PJSUA2,
) {
    val sipConfigured: Boolean get() = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    override fun toString() =
        "GatewaySettings(takeover=$takeover muteEarpiece=$muteEarpiece stack=$sipStack server=$server:$port/$sipTransport srtp=$srtp user=$username target=$inboundTarget)"
}

enum class SipStack(val id: String) {
    /** The hand-written Kotlin stack in :sip. */
    BUILTIN("builtin"),

    /** pjsip's pjsua2 via :sip-pjsua2. */
    PJSUA2("pjsua2"),
    ;

    companion object {
        fun of(id: String?): SipStack? = entries.firstOrNull { it.id == id }
    }
}

/** SharedPreferences, with the SIP password sealed by an Android Keystore AES-GCM key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun load(): GatewaySettings = GatewaySettings(
        takeover = prefs.getBoolean("takeover", true),
        muteEarpiece = prefs.getBoolean("mute_earpiece", true),
        server = prefs.getString("server", "")!!,
        port = prefs.getInt("port", 5060),
        sipTransport = prefs.getString("sip_transport", "udp")!!,
        srtp = prefs.getString("srtp", "optional")!!,
        sipDebugLog = prefs.getBoolean("sip_debug_log", false),
        username = prefs.getString("username", "")!!,
        password = prefs.getString("password_sealed", null)?.let(::unseal).orEmpty(),
        domain = prefs.getString("domain", "")!!,
        localSipPort = prefs.getInt("local_sip_port", 5062),
        inboundTarget = prefs.getString("inbound_target", "")!!,
        allowedPrefixes = prefs.getString("allowed_prefixes", "")!!,
        busyPolicy = runCatching { BusyPolicy.valueOf(prefs.getString("busy_policy", "REJECT")!!) }.getOrDefault(BusyPolicy.REJECT),
        audioMethod = prefs.getInt("audio_method", AudioMethod.AUTO).takeIf { it in 0..2 } ?: AudioMethod.AUTO,
        callAccess = prefs.getInt("call_access", CallAccess.AUTO).takeIf { it in 0..2 } ?: CallAccess.AUTO,
        sipStack = SipStack.of(prefs.getString("sip_stack", null)) ?: SipStack.PJSUA2,
    )

    fun save(s: GatewaySettings) {
        prefs.edit()
            .putBoolean("takeover", s.takeover)
            .putBoolean("mute_earpiece", s.muteEarpiece)
            .putString("server", s.server.trim())
            .putInt("port", s.port)
            .putString("sip_transport", s.sipTransport.trim().lowercase())
            .putString("srtp", s.srtp.trim().lowercase())
            .putBoolean("sip_debug_log", s.sipDebugLog)
            .putString("username", s.username.trim())
            .putString("password_sealed", seal(s.password))
            .putString("domain", s.domain.trim())
            .putInt("local_sip_port", s.localSipPort)
            .putString("inbound_target", s.inboundTarget.trim())
            .putString("allowed_prefixes", s.allowedPrefixes.trim())
            .putString("busy_policy", s.busyPolicy.name)
            .putInt("audio_method", s.audioMethod)
            .putInt("call_access", s.callAccess)
            .putString("sip_stack", s.sipStack.id)
            .apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        AppLog.w("stored SIP password unreadable; re-enter it", e)
        null
    }

    companion object {
        private const val KEY_ALIAS = "sip-password"
    }
}
