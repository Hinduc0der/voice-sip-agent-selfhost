package io.github.sparkcyf.voicesipagent.privileged.shell

import android.app.role.RoleManager
import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState

/** All calls run after the service validates its caller and clears the Binder identity. */
internal class ShellDialerPlatform(private val context: Context, appUid: Int) : DialerRoles.Platform {
    private val user = UserHandle.getUserHandleForUid(appUid)
    private val userId = UserHandle::class.java.getMethod("getIdentifier").invoke(user) as Int
    private val roles get() = context.getSystemService(RoleManager::class.java)

    override fun state(): DialerRoleState {
        val manager = roles
        val holders = RoleManager::class.java.getMethod("getRoleHoldersAsUser", String::class.java, UserHandle::class.java)
            .invoke(manager, RoleManager.ROLE_DIALER, user) as List<*>
        check(holders.size <= 1) { "Dialer role unexpectedly has multiple holders" }
        val telecom = runCatching { telecomService() }.getOrNull()
        val fallback = runCatching { telecom?.javaClass?.getMethod("getSystemDialerPackage")?.invoke(telecom) as? String }.getOrNull().orEmpty()
        // Call the Binder interface directly: TelecomManager.isInCall() returns false on IPC failure.
        val inCall = runCatching {
            checkNotNull(telecom) { "Telecom unavailable" }
            val pkg = if (Process.myUid() == ShellEnv.SHELL_UID) ShellEnv.SHELL_PACKAGE else ""
            telecom.javaClass.getMethod("isInCall", String::class.java, String::class.java).invoke(telecom, pkg, null) as Boolean
        }.getOrNull()
        return DialerRoleState(userId, holders.firstOrNull() as? String ?: "", fallback,
            manager.isRoleAvailable(RoleManager.ROLE_DIALER),
            ShellEnv.hasPermission("android.permission.MANAGE_ROLE_HOLDERS", context), inCall)
    }

    override fun addHolder(userId: Int, packageName: String) = command("add-role-holder", userId, packageName)
    override fun removeHolder(userId: Int, packageName: String) = command("remove-role-holder", userId, packageName)

    private fun command(operation: String, userId: Int, pkg: String): ShellEnv.Result {
        check(userId == this.userId) { "Wrong Android user" }
        require(pkg.isNotEmpty() && !pkg.startsWith('-')) { "Invalid dialer package" }
        return ShellEnv.run("cmd", "role", operation, "--user", userId.toString(), RoleManager.ROLE_DIALER, pkg, "1")
    }

    private fun telecomService(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "telecom") as? IBinder ?: error("Telecom unavailable")
        return Class.forName("com.android.internal.telecom.ITelecomService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("Telecom unavailable")
    }
}
