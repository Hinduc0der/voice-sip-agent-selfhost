package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.BUSY
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.CHANGED
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.FAILED
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult.Companion.SUCCESS
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleState

/** Role commands and observed results, independent of Binder/UI lifecycle. */
internal class DialerRoles(private val gateway: String, private val platform: Platform) {
    interface Platform {
        fun state(): DialerRoleState
        fun addHolder(userId: Int, packageName: String): ShellEnv.Result
        fun removeHolder(userId: Int, packageName: String): ShellEnv.Result
    }

    @Synchronized
    fun acquire(expectedHolder: String): DialerRoleResult {
        val before = platform.state()
        if (before.holder == gateway) return DialerRoleResult(SUCCESS, "Already the default dialer.", before)
        checkChange(before, expectedHolder)?.let { return it }
        val command = platform.addHolder(before.userId, gateway)
        val after = platform.state()
        return if (after.holder == gateway) DialerRoleResult(SUCCESS, "This app is now the default dialer.", after)
        else DialerRoleResult(FAILED, "Default dialer did not become this app. ${describe(command)}", after)
    }

    @Synchronized
    fun restore(previousHolder: String): DialerRoleResult {
        var state = platform.state()
        if (state.holder != gateway) return DialerRoleResult(CHANGED, "Default dialer has already changed; leaving that choice in place.", state)
        val errors = mutableListOf<String>()
        for (target in listOf(previousHolder, state.fallback).filter { it.isNotEmpty() && it != gateway }.distinct()) {
            // Recheck for a new call or a user-selected holder before every fallback attempt.
            state = platform.state()
            checkChange(state, gateway)?.let { return it }
            val command = platform.addHolder(state.userId, target)
            state = platform.state()
            if (state.holder == target) return DialerRoleResult(SUCCESS,
                if (target == previousHolder) "Previous dialer restored." else "System dialer selected; the previous dialer was unavailable.", state)
            if (state.holder != gateway) return DialerRoleResult(FAILED,
                "Default dialer changed during restoration. Open system default-app settings to check the selection.", state)
            if (command.exit == 0 || command.out.contains("timeout", ignoreCase = true)) return DialerRoleResult(FAILED,
                "Could not verify dialer restoration; no further role changes were attempted. ${describe(command)}", state)
            errors += "$target: ${describe(command)}"
        }
        // Removal asks the controller to install its configured fallback, including OEM choices.
        state = platform.state()
        checkChange(state, gateway)?.let { return it }
        val command = platform.removeHolder(state.userId, gateway)
        state = platform.state()
        return if (state.holder.isNotEmpty() && state.holder != gateway) DialerRoleResult(SUCCESS,
            "Default dialer restored to the system fallback.", state)
        else DialerRoleResult(FAILED, "Could not restore a dialer. Open system default-app settings. " +
            (errors + describe(command)).joinToString("; "), state)
    }

    private fun checkChange(state: DialerRoleState, expected: String): DialerRoleResult? = when {
        state.holder != expected -> DialerRoleResult(CHANGED, "Default dialer changed; leaving the new choice in place.", state)
        !state.available -> DialerRoleResult(FAILED, "Default dialer selection is unavailable on this device.", state)
        !state.canManage -> DialerRoleResult(FAILED, "The Shizuku helper cannot manage default-app roles. Use system selection.", state)
        state.inCall == null -> DialerRoleResult(FAILED, "Could not verify that Telecom is idle. Try again or use system selection.", state)
        state.inCall == true -> DialerRoleResult(BUSY, "Waiting for current calls to end.", state)
        else -> null
    }

    private fun describe(result: ShellEnv.Result) = "exit=${result.exit}: ${result.out.trim().take(240)}"
}
