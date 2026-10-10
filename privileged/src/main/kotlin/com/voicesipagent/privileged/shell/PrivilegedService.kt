package com.voicesipagent.privileged.shell

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.voicesipagent.privileged.HealthKeys
import com.voicesipagent.privileged.IPrivilegedService
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
    private var mutedByUs = false

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

    override fun setup(packageName: String): Bundle {
        enforceCaller(packageName)
        val log = StringBuilder()
        fun step(name: String, vararg cmd: String) {
            val r = ShellEnv.run(*cmd)
            log.append(name).append('=').append(if (r.exit == 0) "ok" else "fail(${r.exit}: ${r.out.trim().take(120)})").append('\n')
        }
        if (!appOpAllowed(packageName)) step("appops", "appops", "set", packageName, "MANAGE_ONGOING_CALLS", "allow")
        // Lost on every reboot and Telecom restart, so always re-apply.
        step("companion", "cmd", "telecom", "add-or-remove-call-companion-app", packageName, "1")
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
        return b
    }

    @Synchronized
    override fun openCallAudio(method: Int): ParcelFileDescriptor {
        enforceCaller()
        closeCallAudio()
        val t = CallAudioTap(shellCtx, method)
        return try {
            t.open().also { tap = t }
        } catch (e: Throwable) {
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

    @Synchronized
    override fun setLocalOutputMuted(muted: Boolean): String {
        enforceCaller()
        if (muted == mutedByUs) return "unchanged"
        // Stream 0 = STREAM_VOICE_CALL; adj-mute keeps the user's volume level for restore.
        val r = ShellEnv.run("cmd", "audio", if (muted) "adj-mute" else "adj-unmute", "0")
        if (r.exit == 0) mutedByUs = muted
        return "exit=${r.exit}"
    }

    private fun appOpAllowed(pkg: String) =
        ShellEnv.run("appops", "get", pkg, "MANAGE_ONGOING_CALLS").out.contains("allow")

    private fun companionOverridden(pkg: String): Boolean {
        val out = ShellEnv.run("dumpsys", "telecom", timeoutSec = 15).out
        // The override list is printed as "DefaultCallCompanionApps: [pkg, ...]" or similar.
        return out.lineSequence().any { it.contains("ompanion", ignoreCase = false) && it.contains(pkg) }
    }

    private fun dozeAllowlisted(pkg: String) =
        ShellEnv.run("cmd", "deviceidle", "whitelist").out.lineSequence().any { it.trim().endsWith(",$pkg") || it.contains(",$pkg,") }

    companion object {
        private const val TAG = "VsaPrivileged"
        private val RUNTIME_PERMISSIONS = listOf(
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
