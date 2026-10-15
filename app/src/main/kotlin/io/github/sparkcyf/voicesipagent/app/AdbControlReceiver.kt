package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb-only control (protected by the DUMP permission). Examples:
 *   adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op status
 *   ... --es op provision --es server pbx.example.org --ei port 5060 --es user gateway --es password '***' [--es target gateway]
 *   ... --es op setup | sip-restart | loopback --ez on true | takeover --ez on false
 *   ... --es op earpiece-mute --ez on false
 *   ... --es op sip-stack --es stack pjsua2|builtin
 *   ... --es op provision --es server sip.example.org --ei port 5061 --es transport tls --es domain sip.example.org [--es srtp optional]
 *   ... --es op sip-debug --ez on true      (pjsip info-level logs; off by default)
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
            "sms-test" -> rt.forwardSms(intent.getStringExtra("from") ?: "+10000000000",
                intent.getStringExtra("text") ?: "test message from the gateway")
            "sip-debug" -> rt.setSipDebugLog(intent.getBooleanExtra("on", true))
            "sip-stack" -> {
                val stack = SipStack.of(intent.getStringExtra("stack"))
                if (stack == null) AppLog.w("adb control: sip-stack needs --es stack builtin|pjsua2")
                else rt.applySettings(rt.settings.copy(sipStack = stack))
            }
            "provision" -> {
                val cur = rt.settings
                rt.applySettings(
                    cur.copy(
                        server = intent.getStringExtra("server") ?: cur.server,
                        port = intent.getIntExtra("port", cur.port),
                        sipTransport = intent.getStringExtra("transport") ?: cur.sipTransport,
                        srtp = intent.getStringExtra("srtp") ?: cur.srtp,
                        username = intent.getStringExtra("user") ?: cur.username,
                        password = intent.getStringExtra("password") ?: cur.password,
                        domain = intent.getStringExtra("domain") ?: cur.domain,
                        inboundTarget = intent.getStringExtra("target") ?: cur.inboundTarget,
                        allowedPrefixes = intent.getStringExtra("prefixes") ?: cur.allowedPrefixes,
                        audioMethod = intent.getIntExtra("audio_method", cur.audioMethod),
                        callAccess = intent.getIntExtra("call_access", cur.callAccess),
                    ),
                )
            }
            else -> AppLog.w("adb control: unknown op '$op'")
        }
    }
}
