package com.iknalos.dataguard

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs [ThrottleProxy] on 0.0.0.0:[PORT] so devices
 * connected to the phone's hotspot can route through it. Unlike the VPN
 * speed cap, this is opt-in per client: each guest sets a manual Wi-Fi proxy
 * pointing at the phone's hotspot IP and this port. Their HTTP/HTTPS traffic
 * is then rate-limited by the shared token bucket, which makes streaming apps
 * drop to lower bitrate and spend far less of the phone's mobile data.
 */
class HotspotProxyService : Service() {

    private var proxy: ThrottleProxy? = null
    private val bucket = TokenBucket(0)

    override fun onBind(intent: Intent?): IBinder? = null

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
        bucket.bytesPerSec = mbps * 125_000L
        currentMbps = mbps
        if (proxy == null) {
            val p = ThrottleProxy(null, bucket, "0.0.0.0", PORT)
            try {
                p.start()
            } catch (e: Exception) {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            proxy = p
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(mbps))
        } catch (e: Exception) {
            // Foreground start can be restricted; proxy still serves clients.
        }
        return START_STICKY
    }

    private fun teardown() {
        currentMbps = 0
        proxy?.close()
        proxy = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        val ip = NetworkUtils.hotspotAddress() ?: "your phone's hotspot IP"
        return NotificationCompat.Builder(this, Notifier.STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle("Hotspot cap on: $mbps Mbps")
            .setContentText("Guests: set Wi-Fi proxy to $ip:$PORT")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "On each connected device, set the Wi-Fi proxy to $ip port $PORT " +
                        "to limit its speed to $mbps Mbps."
                )
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.iknalos.dataguard.STOP_HOTSPOT"
        const val EXTRA_MBPS = "mbps"
        const val PORT = 8888
        private const val NOTIFICATION_ID = 3

        @Volatile
        var currentMbps = 0
    }
}
