package com.voicesipagent.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.voicesipagent.privileged.shell.PrivilegedService
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-side lifecycle for the Shizuku UserService: waits for the Shizuku binder (it appears ~15 s
 * after boot via wireless debugging), checks permission, binds [PrivilegedService] and reports
 * every transition so the app can re-run setup when the service (re)connects.
 */
class ShizukuConnector(context: Context, private val log: (String) -> Unit) {
    enum class State { SHIZUKU_NOT_RUNNING, PERMISSION_REQUIRED, PERMISSION_DENIED, BINDING, CONNECTED, ERROR }

    fun interface Listener {
        fun onChanged(state: State, service: IPrivilegedService?)
    }

    private val app = context.applicationContext
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var state = State.SHIZUKU_NOT_RUNNING; private set
    @Volatile var service: IPrivilegedService? = null; private set
    @Volatile var detail: String = ""; private set

    private val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, PrivilegedService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .tag("vsa-privileged")
        // Restart the shell process whenever a new APK is installed so it never runs stale code.
        .version((app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                set(State.ERROR, null, "UserService binder dead")
                return
            }
            val svc = IPrivilegedService.Stub.asInterface(binder)
            val uid = try { svc.uid } catch (e: Exception) { -1 }
            if (uid != 2000 && uid != 0) {
                set(State.ERROR, null, "unexpected privileged uid $uid")
                return
            }
            set(State.CONNECTED, svc, "uid $uid")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            set(State.BINDING, null, "UserService disconnected")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { onBinder() }
    private val binderDead = Shizuku.OnBinderDeadListener { set(State.SHIZUKU_NOT_RUNNING, null, "Shizuku binder died") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) bind() else set(State.PERMISSION_DENIED, null, "denied in Shizuku")
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        l.onChanged(state, service)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Asks Shizuku for permission (shows Shizuku's dialog). Call from the UI. */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) return set(State.SHIZUKU_NOT_RUNNING, null, "start Shizuku first")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "permission request failed: ${e.message}")
        }
    }

    private fun onBinder() {
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return set(State.ERROR, null, "Shizuku v13+ required")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else set(State.PERMISSION_REQUIRED, null, "grant access in Shizuku")
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "Shizuku check failed: ${e.message}")
        }
    }

    private fun bind() {
        if (state == State.CONNECTED && service?.asBinder()?.pingBinder() == true) return
        set(State.BINDING, null, "binding UserService")
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "bindUserService failed: ${e.message}")
        }
    }

    private fun set(s: State, svc: IPrivilegedService?, why: String) {
        state = s
        service = svc
        detail = why
        log("shizuku: $s ($why)")
        listeners.forEach { it.onChanged(s, svc) }
    }

    companion object {
        private const val PERMISSION_REQUEST = 4101
    }
}
