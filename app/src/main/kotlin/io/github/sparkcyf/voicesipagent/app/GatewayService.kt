package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process and SIP registration alive while takeover is on, with a status notification.
 * When takeover is off, keeps a reminder visible while this app still holds the dialer role.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var foreground = false
    private val runtimeToken = Any()
    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GatewayRuntime.get(this@GatewayService).refreshCallAccess()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        runtime.onServiceCreated(runtimeToken)
        ContextCompat.registerReceiver(this, roleReceiver,
            IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        runtime.refreshCallAccess()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = GatewayRuntime.get(this)
        if (!runtime.health.value.needsForegroundService) {
            stopGateway(startId)
            return START_NOT_STICKY
        }
        // Recheck at each start, including sticky restarts and rapid off/on transitions.
        if (!foreground) {
            try {
                promote(runtime.health.value)
            } catch (e: Exception) {
                AppLog.w("could not promote gateway service", e)
                stopGateway(startId)
                return START_NOT_STICKY
            }
        }
        runtime.onForegroundStarted(runtimeToken)
        if (statusJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            statusJob = scope.launch {
                runtime.health.map { it.needsForegroundService to summary(it) }.distinctUntilChanged().collect { (needed, text) ->
                    if (needed) nm.notify(NOTIFICATION_ID, build(text)) else stopGateway()
                }
            }
        }
        return START_STICKY
    }

    private fun promote(health: HealthState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gateway status", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, build(summary(health)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, build(summary(health)))
        }
        foreground = true
    }

    private fun stopGateway(startId: Int? = null) {
        statusJob?.cancel()
        statusJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GatewayRuntime.get(this).onForegroundStopped(runtimeToken)
        unregisterReceiver(roleReceiver)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun summary(h: HealthState): String = when {
        h.session != null -> "In call · ${h.session}"
        !h.takeover && h.holdsDialer -> "Takeover off. Still your default phone app; change it in Settings."
        !h.takeover -> "Call takeover off"
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
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val runtime = GatewayRuntime.get(context)
            runtime.start()
            sync(context, runtime.health.value)
        }

        /** Used for both explicit launches and runtime switch/role changes. */
        internal fun sync(context: Context, health: HealthState): Boolean {
            if (!health.needsForegroundService) {
                context.stopService(Intent(context, GatewayService::class.java))
                return true
            }
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
                return true
            } catch (e: Exception) {
                // Background-start restrictions; the activity or boot receiver will retry.
                AppLog.w("could not start foreground service", e)
                return false
            }
        }
    }
}
