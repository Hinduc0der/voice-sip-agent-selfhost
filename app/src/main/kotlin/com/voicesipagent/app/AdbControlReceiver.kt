package com.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb-only control (protected by the DUMP permission). Examples:
 *   adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op status
 *   ... --es op provision --es server 192.168.0.53 --ei port 5066 --es user 401 --es password '***' [--es target 302]
 *   ... --es op setup | sip-restart | loopback --ez on true | takeover --ez on false
 *   ... --es op earpiece-mute --ez on false
 */
class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rt = GatewayRuntime.get(context)
        when (val op = intent.getStringExtra("op")) {
            "status" -> rt.statusReport().lines().filter { it.isNotBlank() }.forEach { AppLog.i("status: $it") }
            "setup" -> rt.runSetup()
            "sip-restart" -> rt.restartSip()
            "loopback" -> rt.setLoopbackTest(intent.getBooleanExtra("on", true))
            "takeover" -> rt.setTakeover(intent.getBooleanExtra("on", true))
            "earpiece-mute" -> rt.setMuteEarpiece(intent.getBooleanExtra("on", true))
            "provision" -> {
                val cur = rt.settings
                rt.applySettings(
                    cur.copy(
                        server = intent.getStringExtra("server") ?: cur.server,
                        port = intent.getIntExtra("port", cur.port),
                        username = intent.getStringExtra("user") ?: cur.username,
                        password = intent.getStringExtra("password") ?: cur.password,
                        domain = intent.getStringExtra("domain") ?: cur.domain,
                        inboundTarget = intent.getStringExtra("target") ?: cur.inboundTarget,
                        allowedPrefixes = intent.getStringExtra("prefixes") ?: cur.allowedPrefixes,
                        audioMethod = intent.getIntExtra("audio_method", cur.audioMethod),
                    ),
                )
            }
            else -> AppLog.w("adb control: unknown op '$op'")
        }
    }
}
