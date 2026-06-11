package com.iknalos.dataguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifier {
    private const val ALERT_CHANNEL = "data_alerts"
    const val STATUS_CHANNEL = "throttle_status"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Data usage alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Speed cap status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun notify(context: Context, title: String, text: String) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(1, notification)
        } catch (e: SecurityException) {
            // Notification permission revoked; nothing we can do.
        }
    }
}
