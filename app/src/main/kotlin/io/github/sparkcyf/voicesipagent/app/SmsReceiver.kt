package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Forwards incoming cellular SMS to the registered softphone as a SIP MESSAGE (server mode).
 * Multipart messages are reassembled; the sender address is taken from the first part.
 * Requires the RECEIVE_SMS runtime permission; the app never becomes the default SMS app.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts.firstOrNull()?.displayOriginatingAddress
        val body = parts.joinToString("") { it.messageBody ?: "" }
        if (body.isEmpty()) return
        AppLog.i("SMS received; relaying to the registered phone")
        GatewayRuntime.get(context).forwardSms(from, body)
    }
}
