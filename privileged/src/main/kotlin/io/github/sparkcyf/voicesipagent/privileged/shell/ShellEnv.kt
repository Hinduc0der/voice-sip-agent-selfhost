package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.IBinder
import java.util.concurrent.TimeUnit

/**
 * Helpers for code running inside the Shizuku UserService process (uid 2000). Audio APIs check
 * the caller's attribution, so we present ourselves as com.android.shell rather than as the app
 * package whose code we are running.
 */
internal object ShellEnv {
    const val SHELL_UID = 2000
    const val SHELL_PACKAGE = "com.android.shell"

    /** Context attributed to the shell package; validated in app_process by the feasibility probe. */
    fun shellContext(fallback: Context): Context = ShellContext(systemContext() ?: fallback)

    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    } catch (t: Throwable) {
        null
    }

    fun audioManager(ctx: Context): AudioManager {
        val c = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
        c.isAccessible = true
        return c.newInstance(ctx)
    }

    fun isPstnCallAudioInterceptable(): Boolean? = try {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val svc = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, b)
        svc.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(svc) as Boolean
    } catch (t: Throwable) {
        null
    }

    data class Result(val exit: Int, val out: String)

    /** Runs a fixed command (never a shell string) with a timeout. */
    fun run(vararg cmd: String, timeoutSec: Long = 10): Result {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(out) { out.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(-1, "timeout")
        }
        reader.join(1000)
        return Result(p.exitValue(), synchronized(out) { out.toString() })
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        override fun getApplicationContext(): Context = this
        override fun getDeviceId() = 0
    }
}
