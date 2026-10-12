package io.github.sparkcyf.voicesipagent.privileged.shell

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/** Kept out of API 30 class loading, including AttributionSource method signatures. */
@TargetApi(31)
internal open class ModernAudioContext private constructor(base: Context, private val uid: Int) : ContextWrapper(base) {
    // Keep a stable source/token for every audio object built with this helper context.
    private val audioAttribution = AttributionSource.Builder(uid)
        .setPackageName(if (uid == ShellEnv.SHELL_UID) ShellEnv.SHELL_PACKAGE else null).build()

    override fun getPackageName() = if (uid == ShellEnv.SHELL_UID) ShellEnv.SHELL_PACKAGE else ""
    override fun getOpPackageName() = packageName
    override fun getAttributionSource(): AttributionSource = audioAttribution
    override fun getApplicationContext(): Context = this

    companion object {
        fun create(base: Context): Context {
            val uid = Process.myUid()
            check(uid == ShellEnv.SHELL_UID || uid == 0) { "unsupported helper uid $uid" }
            return if (Build.VERSION.SDK_INT >= 34) DeviceContext(base, uid) else ModernAudioContext(base, uid)
        }

        /** Framework call helpers use this process-default source, ignoring the builder context. */
        fun hasMatchingDefaultAttribution(context: Context): Boolean = runCatching {
            val source = AttributionSource::class.java.getMethod("myAttributionSource").invoke(null) as AttributionSource
            val expected = context.attributionSource
            source.uid == expected.uid && source.packageName == expected.packageName
        }.getOrDefault(false)
    }

    @TargetApi(34)
    private class DeviceContext(base: Context, uid: Int) : ModernAudioContext(base, uid) {
        override fun getDeviceId() = 0
    }
}
