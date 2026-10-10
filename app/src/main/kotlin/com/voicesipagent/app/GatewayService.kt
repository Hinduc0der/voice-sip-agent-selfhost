package com.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the process (and so the SIP registration) alive, with a status notification. */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gateway status", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build("starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        scope.launch {
            runtime.health.map(::summary).distinctUntilChanged().collect { nm.notify(NOTIFICATION_ID, build(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun summary(h: HealthState): String = when {
        h.session != null -> "In call · ${h.session}"
        h.loopbackTest -> "Loopback test mode"
        !h.privilegedReady -> "Setup needed · Shizuku ${h.shizuku.name.lowercase()}"
        !h.sip.ready -> "SIP: ${h.sip.detail}"
        else -> "Ready · ${h.sip.detail}"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
            } catch (e: Exception) {
                // Background-start restrictions; the activity or boot receiver will retry.
                AppLog.w("could not start foreground service", e)
            }
        }
    }
}
