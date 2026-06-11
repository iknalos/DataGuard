package com.iknalos.dataguard

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

/**
 * VpnService that enforces the speed cap. It routes all traffic into a TUN,
 * publishes a device-wide HTTP proxy backed by [ThrottleProxy] (which does
 * the actual rate limiting), and forwards DNS via [TunDnsForwarder].
 */
class ThrottleVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: ThrottleProxy? = null
    private var dns: TunDnsForwarder? = null
    private val bucket = TokenBucket(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        val mbps = intent?.getIntExtra(EXTRA_MBPS, 0) ?: 0
        if (mbps <= 0) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        bucket.bytesPerSec = mbps * 125_000L // Mbps -> bytes/sec
        currentMbps = mbps
        if (tun == null && !establish()) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(mbps))
        } catch (e: Exception) {
            // Foreground start can be restricted; the VPN still works without it.
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        val p = ThrottleProxy(this, bucket)
        p.start()
        proxy = p
        val builder = Builder()
            .setSession("DataGuard speed cap")
            .setMtu(1500)
            .addAddress("10.111.222.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.111.222.53")
            .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", p.port))
            .setBlocking(true)
        try {
            // Capture IPv6 too, otherwise QUIC over IPv6 bypasses the cap.
            builder.addAddress("fd00:da7a:6a4d::1", 64)
            builder.addRoute("::", 0)
        } catch (e: Exception) {
            // IPv6 unavailable; IPv4 capture still applies.
        }
        val fd = builder.establish() ?: return false
        tun = fd
        dns = TunDnsForwarder(this, fd).also { it.start() }
        return true
    }

    private fun teardown() {
        currentMbps = 0
        dns?.stop()
        dns = null
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

    private fun buildNotification(mbps: Int): Notification {
        Notifier.ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifier.STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle("Speed capped at $mbps Mbps")
            .setContentText("DataGuard is limiting your connection to save data.")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.iknalos.dataguard.STOP_THROTTLE"
        const val EXTRA_MBPS = "mbps"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var currentMbps = 0
    }
}
