package io.github.sparkcyf.voicesipagent.privileged.shell

import android.annotation.TargetApi
import android.app.Application
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
        private var processAttributionPrepared = false

        fun create(base: Context): Context {
            val uid = Process.myUid()
            check(uid == ShellEnv.SHELL_UID || uid == 0) { "unsupported helper uid $uid" }
            return if (Build.VERSION.SDK_INT >= 34) DeviceContext(base, uid) else ModernAudioContext(base, uid)
        }

        /** Framework call helpers use this process-default source, ignoring the builder context. */
        fun hasMatchingDefaultAttribution(context: Context): Boolean = runCatching {
            val source = AttributionSource::class.java.getMethod("myAttributionSource").invoke(null) as AttributionSource
            val expected = context.attributionSource
            source.uid == expected.uid && source.packageName.orEmpty() == expected.packageName.orEmpty()
        }.getOrDefault(false)

        /**
         * The audited API 32 default source uses currentOpPackageName(); API 33 prefers the
         * Application attribution source. Tracks still read the operation package on both.
         * Override both while retaining the Application's real package/context.
         */
        @Synchronized
        fun prepareProcessAttribution(context: Context) {
            if (processAttributionPrepared) return
            val at = Class.forName("android.app.ActivityThread")
            val application = at.getMethod("currentApplication").invoke(null) as? Application
            val expected = context.attributionSource
            if (application == null) {
                // Older bootstrap: an empty default package is resolved by native audio under
                // the process UID. Verify that identity rather than inventing an Application.
                val source = AttributionSource::class.java.getMethod("myAttributionSource").invoke(null) as AttributionSource
                check(source.uid == expected.uid &&
                    (source.packageName.isNullOrEmpty() || source.packageName == expected.packageName)) {
                    "context-free audio attribution does not match helper uid=${expected.uid}"
                }
            } else {
                val original = application.baseContext
                val wrapper = object : ContextWrapper(original) {
                    override fun getOpPackageName() = context.opPackageName
                    override fun getAttributionSource(): AttributionSource = expected
                }
                val field = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
                field.set(application, wrapper)
                try {
                    check(at.getMethod("currentOpPackageName").invoke(null) == context.opPackageName &&
                        hasMatchingDefaultAttribution(context)) {
                        "context-free audio attribution adapter did not take effect"
                    }
                } catch (failure: Throwable) {
                    field.set(application, original)
                    throw failure
                }
            }
            processAttributionPrepared = true
        }
    }

    @TargetApi(34)
    private class DeviceContext(base: Context, uid: Int) : ModernAudioContext(base, uid) {
        override fun getDeviceId() = 0
    }
}
