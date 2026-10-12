package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.Context
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import kotlin.system.exitProcess

/**
 * Shizuku UserService, instantiated by Shizuku inside an app_process running as shell.
 * Only the owning app (same uid that bound us) may call in.
 */
class PrivilegedService(context: Context) : IPrivilegedService.Stub() {
    private val appUid = context.applicationInfo.uid
    private val appPackage = context.packageName
    private val shellCtx = ShellEnv.shellContext(context)
    private var tap: CallAudioTap? = null
    private var lastTapStats = "no tap yet"
    private val audioManager by lazy { ShellEnv.audioManager(shellCtx) }

    /** Guards [localMuted]; never held while taking the service or tap monitors (tap close calls back in). */
    private val muteLock = Any()
    private var localMuted = false

    init {
        Log.i(TAG, "created uid=${Process.myUid()} for $appPackage")
    }

    private fun enforceCaller(pkg: String? = null) {
        val caller = Binder.getCallingUid()
        if (caller != appUid && caller != Process.myUid()) throw SecurityException("caller uid $caller not allowed")
        if (pkg != null && pkg != appPackage) throw SecurityException("package $pkg not allowed")
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        closeCallAudio()
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun setup(packageName: String, takeover: Boolean): Bundle {
        enforceCaller(packageName)
        val log = StringBuilder()
        fun step(name: String, vararg cmd: String) {
            val r = ShellEnv.run(*cmd)
            log.append(name).append('=').append(if (r.exit == 0) "ok" else "fail(${r.exit}: ${r.out.trim().take(120)})").append('\n')
        }
        if (!appOpAllowed(packageName)) step("appops", "appops", "set", packageName, "MANAGE_ONGOING_CALLS", "allow")
        // The override is lost on every reboot and Telecom restart, so it is re-checked each time. Telecom
        // keeps it as a plain list: adding appends a duplicate and removing drops one entry.
        val entries = companionOverrideCount(packageName)
        if (takeover && entries == 0) {
            step("companion", "cmd", "telecom", "add-or-remove-call-companion-app", packageName, "1")
        } else if (!takeover) {
            repeat(entries) { step("companion-remove", "cmd", "telecom", "add-or-remove-call-companion-app", packageName, "0") }
        }
        if (!dozeAllowlisted(packageName)) step("deviceidle", "cmd", "deviceidle", "whitelist", "+$packageName")
        for (perm in RUNTIME_PERMISSIONS) step("grant:${perm.substringAfterLast('.')}", "pm", "grant", packageName, perm)
        Log.i(TAG, "setup:\n$log")
        return health(packageName).apply { putString("setup_log", log.toString()) }
    }

    override fun health(packageName: String): Bundle {
        enforceCaller(packageName)
        val b = Bundle()
        b.putInt(HealthKeys.UID, Process.myUid())
        b.putBoolean(HealthKeys.MANAGE_ONGOING_CALLS, appOpAllowed(packageName))
        b.putBoolean(HealthKeys.COMPANION_OVERRIDE, companionOverridden(packageName))
        b.putBoolean(HealthKeys.DOZE_ALLOWLISTED, dozeAllowlisted(packageName))
        val icp = ShellEnv.isPstnCallAudioInterceptable()
        b.putBoolean(HealthKeys.INTERCEPTABLE, icp == true)
        b.putString(HealthKeys.DETAIL, if (icp == null) "isPstnCallAudioInterceptable unavailable" else "")
        val capabilities = asHelper { AudioCapabilities.inspect(shellCtx) }
        b.putBoolean(HealthKeys.DIRECT_AUDIO, capabilities.direct)
        b.putBoolean(HealthKeys.REDIRECT_AUDIO, capabilities.redirect)
        b.putBoolean(HealthKeys.LOCAL_SUPPRESSION, capabilities.suppression)
        b.putString(HealthKeys.AUDIO_DETAIL, capabilities.detail)
        return b
    }

    @Synchronized
    override fun openCallAudio(method: Int, sampleRate: Int): ParcelFileDescriptor {
        enforceCaller()
        closeCallAudio()
        val t = CallAudioTap(shellCtx, method, sampleRate) { restoreLocal("tap closed") }
        return try {
            asHelper { t.open() }.also { tap = t }
        } catch (e: Throwable) {
            lastTapStats = t.stats()
            Log.w(TAG, "openCallAudio failed: $e")
            throw IllegalStateException(e.message ?: e.toString())
        }
    }

    @Synchronized
    override fun closeCallAudio() {
        enforceCaller()
        tap?.let {
            it.close()
            lastTapStats = it.stats()
        }
        tap = null
    }

    override fun audioStats(): String {
        enforceCaller()
        return tap?.stats() ?: lastTapStats
    }

    override fun setLocalOutputMuted(muted: Boolean): String {
        enforceCaller()
        return asHelper { if (muted) muteLocal() else restoreLocal("requested") }
    }

    private inline fun <T> asHelper(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(token) }
    }

    /**
     * Switches the audio mode from IN_CALL to CALL_REDIRECT. Audio policy then tears down the
     * modem<->earpiece/mic patches while the redirected record/track keep working. Shell holds
     * MODIFY_PHONE_STATE, so its (newer) mode request outranks Telecom's IN_CALL. AudioService drops
     * the request if this process dies, so a crash cannot leave the phone stuck in this mode.
     */
    private fun muteLocal(): String = synchronized(muteLock) {
        if (Build.VERSION.SDK_INT < 33) return "unavailable: handset suppression requires API 33"
        if (localMuted) return "already muted"
        if (tap == null) return "no tap open"
        if (tap?.usedMethod?.startsWith("redirection") != true) return "unavailable for direct telephony audio"
        val before = audioManager.mode
        if (before != AudioManager.MODE_IN_CALL) return "skipped: mode=$before"
        audioManager.mode = MODE_CALL_REDIRECT
        val after = awaitMode(MODE_CALL_REDIRECT)
        localMuted = after == MODE_CALL_REDIRECT
        val r = "mode $before->$after"
        Log.i(TAG, "local mute: $r")
        r
    }

    private fun restoreLocal(why: String): String = synchronized(muteLock) {
        if (!localMuted) return "not muted"
        localMuted = false
        // NORMAL withdraws our request; the mode falls back to Telecom's (IN_CALL, or NORMAL after hangup).
        audioManager.mode = AudioManager.MODE_NORMAL
        val r = "restored ($why), mode=${awaitMode(-1)}"
        Log.i(TAG, "local mute: $r")
        r
    }

    /** Mode changes are applied asynchronously by AudioService; wait briefly for [want] (-1: just settle). */
    private fun awaitMode(want: Int): Int {
        val end = SystemClock.elapsedRealtime() + 600
        var m = audioManager.mode
        while (m != want && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(50)
            m = audioManager.mode
        }
        return m
    }

    private fun appOpAllowed(pkg: String) =
        ShellEnv.run("appops", "get", pkg, "MANAGE_ONGOING_CALLS").out.contains("allow")

    private fun companionOverridden(pkg: String) = companionOverrideCount(pkg) > 0

    /** How many times [pkg] appears in Telecom's companion override list. */
    private fun companionOverrideCount(pkg: String): Int {
        val out = ShellEnv.run("dumpsys", "telecom", timeoutSec = 15).out
        // Printed as "DefaultCallCompanionApps: (override <pkg>, <pkg>, ...)".
        val line = out.lineSequence().firstOrNull { it.contains("DefaultCallCompanionApps") } ?: return 0
        return Regex("[\\s(,]${Regex.escape(pkg)}(?=[,)\\s])").findAll(line).count()
    }

    private fun dozeAllowlisted(pkg: String) =
        ShellEnv.run("cmd", "deviceidle", "whitelist").out.lineSequence().any { it.trim().endsWith(",$pkg") || it.contains(",$pkg,") }

    companion object {
        private const val TAG = "VsaPrivileged"
        /** AudioManager.MODE_CALL_REDIRECT (API 33, system API). */
        private const val MODE_CALL_REDIRECT = 5
        private val RUNTIME_PERMISSIONS = listOf(
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
