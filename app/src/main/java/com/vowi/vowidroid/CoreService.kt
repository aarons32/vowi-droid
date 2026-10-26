package com.vowi.vowidroid

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.LineStore
import com.vowi.vowidroid.telecom.CallManager
import com.vowi.vowidroid.telecom.CallNotifications
import com.vowi.vowidroid.telecom.PhoneAccounts
import com.vowi.vowidroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the native core and its WiFi Calling sessions alive while the UI is
 * closed. The tunnels need keepalives every 20 s and periodic IMS
 * re-registration, so the CPU and Wi-Fi are held awake while it runs.
 */
class CoreService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Some phones switch their SIMs off in airplane mode; the lines then say so, and come back when it ends. */
    private val airplaneWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = VowiCore.rescan()
    }
    private var phoneSims: PhoneSims? = null
    private var simWatch: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** The language the status notification was last written in. */
    private var locales: LocaleList? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = resources.configuration.locales
        if (locales != null && now != locales) promoteToForeground()
        locales = now
    }

    override fun onCreate() {
        super.onCreate()
        if (!UserAgreement.accepted(this)) {
            stopSelf()
            return
        }
        if (!promoteToForeground()) {
            // Android 12+ refuses a background (e.g. sticky) restart; the next launch starts it again.
            stopSelf()
            return
        }
        acquireLocks()
        locales = resources.configuration.locales
        VowiCore.start(this)
        phoneSims = PhoneSims(this).also { sims -> simWatch = sims.watch { VowiCore.rescan() } }
        ContextCompat.registerReceiver(this, airplaneWatch, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        scope.launch {
            combine(VowiCore.status, VowiCore.registered, CallManager.state) { _, _, _ -> }.collect { promoteToForeground() }
        }
        scope.launch {
            VowiCore.lines.collect {
                PhoneAccounts.sync(this@CoreService, it)
                NotificationChannels.sync(this@CoreService, LineStore(this@CoreService).all().map { line -> line.id to line.name })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UserAgreement.accepted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                VowiCore.markStoppedByUser()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESCAN -> VowiCore.rescan()
            // The notification was posted again above, which is all a refresh asks for.
            ACTION_REFRESH -> Unit
            else -> VowiCore.start(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(airplaneWatch) }
        simWatch?.let { phoneSims?.unwatch(it) }
        VowiCore.stop()
        releaseLocks()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean {
        val call = CallManager.state.value?.takeIf { it.phase != CallManager.Phase.INCOMING && it.phase != CallManager.Phase.ENDED }
        val notification = if (call != null) CallNotifications.ongoing(this, call) else notification(VowiCore.status.value, VowiCore.registered.value)
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (call != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (CallManager.hasMicrophone(this)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                PermissionDenials.report(AppPermissions.Need.MICROPHONE, R.string.denial_microphone)
            }
        }
        // Changing the foreground notification's ID makes Android remove the old one. The call's notification must go
        // that way: rewritten in place into the status one, One UI's Now Bar kept showing the call for minutes.
        val id = if (call != null) CALL_NOTIFICATION_ID else NOTIFICATION_ID
        return try {
            ServiceCompat.startForeground(this, id, notification, type)
            if (call == null) NotificationManagerCompat.from(this).cancel(CALL_NOTIFICATION_ID)
            true
        } catch (e: RuntimeException) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException -> {
                    Log.w(TAG, "foreground start refused", e)
                    false
                }
                e is SecurityException && call != null -> {
                    Log.w(TAG, "call foreground types refused", e)
                    val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
                    ServiceCompat.startForeground(this, id, notification, fallback)
                    true
                }
                else -> throw e
            }
        }
    }

    private fun notification(status: VowiCore.Status, registered: Map<String, String>): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CoreService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        // The same words as the app's title: "<line> online", in the order the lines were added.
        val saved = LineStore(this).all()
        val online = saved.filter { it.id in registered }.map { it.name } + registered.keys.filter { id -> saved.none { it.id == id } }
        val lines = VowiCore.lines.value
        val text = when {
            !status.running -> getString(R.string.notification_starting)
            online.isNotEmpty() -> getString(R.string.title_online, online.joinToString(getString(R.string.list_separator)))
            lines.any { it.wifiCallingEnabled && it.present && it.sim == Line.SimStatus.IN_USE } -> getString(R.string.title_connecting)
            else -> getString(R.string.title_offline)
        }
        return NotificationCompat.Builder(this, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // Held for the service's lifetime: without them Doze defers the tunnel's
    // keepalive timers and the ePDG drops the session.
    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VowiDroid:core").apply {
            setReferenceCounted(false)
            acquire()
        }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VowiDroid:core").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "VowiService"
        private const val NOTIFICATION_ID = 1
        /** The ongoing call's own notification ID; see promoteToForeground. */
        private const val CALL_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.vowi.vowidroid.STOP"
        const val ACTION_RESCAN = "com.vowi.vowidroid.RESCAN"
        private const val ACTION_REFRESH = "com.vowi.vowidroid.REFRESH"

        /** Starts the service, once the user agreement has been accepted. */
        fun start(context: Context, action: String? = null) {
            if (!UserAgreement.accepted(context)) return
            ContextCompat.startForegroundService(context, Intent(context, CoreService::class.java).setAction(action))
        }

        /** Posts the status notification again, e.g. in a new language; nothing while the service is not running. */
        fun refresh(context: Context) {
            if (VowiCore.isStarted) start(context, ACTION_REFRESH)
        }
    }
}
