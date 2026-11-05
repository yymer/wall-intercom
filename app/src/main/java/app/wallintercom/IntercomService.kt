package app.wallintercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app alive in the background so the other tablet can always reach it:
 * foreground notification + CPU and Wi-Fi locks. Wakes the screen on an incoming call.
 */
class IntercomService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            1,
            builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        )

        CallManager.init(this)
        CallManager.onWakeUp = { wakeAndShow() }

        cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallintercom:cpu")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wallintercom:wifi")
            .apply { setReferenceCounted(false); acquire() }
        // Many Wi-Fi drivers drop broadcasts (tablet discovery) unless someone asks for them.
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("wallintercom:discovery")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        cpuLock?.release()
        wifiLock?.release()
        multicastLock?.release()
        super.onDestroy()
    }

    private fun wakeAndShow() {
        @Suppress("DEPRECATION")
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "wallintercom:wake"
            )
            .acquire(15_000)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    companion object {
        private const val CHANNEL = "intercom"
        fun start(ctx: Context) {
            val i = Intent(ctx, IntercomService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

/** Starts the app when the tablet is turned on, if "Start automatically" is ticked in settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != QUICKBOOT) return
        if (!Prefs(context).autoStart) return
        // The service first: it listens for calls even if Android doesn't let us show the screen.
        IntercomService.start(context)
        // Showing the photo screen from the background needs "Allow waking up over other apps"
        // on Android 10+ (granted in settings); without it, only the service runs.
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "can't open the app at boot: ${e.message}")
        }
    }

    companion object {
        /** Sent instead of BOOT_COMPLETED after a "fast boot" on some Lenovo/HTC tablets. */
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}

class AdminReceiver : android.app.admin.DeviceAdminReceiver()
