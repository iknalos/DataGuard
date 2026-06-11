package com.iknalos.dataguard

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

/**
 * VpnService that enforces speed caps. It brings up a VPN purely to publish a
 * device-wide HTTP proxy ([ThrottleProxy]) and routes only an unused subnet,
 * so real traffic flows normally and is never black-holed. Each proxied
 * connection is rate-limited by the bucket [BucketRegistry] chooses for the
 * owning app — a per-app cap if set, otherwise the global cap.
 */
class ThrottleVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: ThrottleProxy? = null
    private lateinit var registry: BucketRegistry

    override fun onCreate() {
        super.onCreate()
        registry = BucketRegistry(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = Prefs(this)
        val globalMbps = prefs.globalMbps
        val caps = prefs.appCaps()
        if (globalMbps <= 0 && caps.isEmpty()) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        registry.update(globalMbps, caps)
        currentMbps = globalMbps
        appCapCount = caps.size
        if (tun == null && !establish()) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(globalMbps, caps.size))
        } catch (e: Exception) {
            // Foreground start can be restricted; the VPN still works without it.
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        val p = ThrottleProxy(
            vpn = this,
            bucket = registry.globalBucket,
            bucketSelector = { registry.bucketForSocket(it) }
        )
        p.start()
        proxy = p
        // Route only our own unused subnet so real traffic is not captured;
        // the VPN exists solely to publish the throttling HTTP proxy. Exclude
        // DataGuard itself so its relayed connections use the real network.
        val builder = Builder()
            .setSession("DataGuard speed cap")
            .setMtu(1500)
            .addAddress("10.111.222.1", 24)
            .addRoute("10.111.222.0", 24)
            .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", p.port))
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // Package lookup failed; proceed without self-exclusion.
        }
        val fd = builder.establish() ?: return false
        tun = fd
        return true
    }

    private fun teardown() {
        currentMbps = 0
        appCapCount = 0
        proxy?.close()
        proxy = null
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun buildNotification(mbps: Int, capCount: Int): Notification {
        Notifier.ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            mbps > 0 && capCount > 0 -> "Speed cap: $mbps Mbps (+$capCount app limit${if (capCount > 1) "s" else ""})"
            mbps > 0 -> "Speed capped at $mbps Mbps"
            else -> "App speed limits active ($capCount app${if (capCount > 1) "s" else ""})"
        }
        return NotificationCompat.Builder(this, Notifier.STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle(title)
            .setContentText("DataGuard is limiting your connection to save data.")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.iknalos.dataguard.STOP_THROTTLE"
        const val ACTION_APPLY = "com.iknalos.dataguard.APPLY_THROTTLE"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var currentMbps = 0

        @Volatile
        var appCapCount = 0
    }
}
