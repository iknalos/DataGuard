package com.iknalos.dataguard

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

data class AppUsage(val uid: Int, val pkg: String?, val label: String, val bytes: Long)

class DataUsageRepository(private val context: Context) {

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val statsManager: NetworkStatsManager
        get() = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    /** Total mobile data (rx + tx) between two timestamps, in bytes. */
    fun getMobileBytes(start: Long, end: Long): Long = try {
        val bucket = statsManager.querySummaryForDevice(
            ConnectivityManager.TYPE_MOBILE, null, start, end
        )
        bucket.rxBytes + bucket.txBytes
    } catch (e: Exception) {
        0L
    }

    /** Raw mobile data per app uid between two timestamps. */
    fun getPerAppBytesRaw(start: Long, end: Long): Map<Int, Long> {
        val totals = HashMap<Int, Long>()
        try {
            val stats = statsManager.querySummary(
                ConnectivityManager.TYPE_MOBILE, null, start, end
            )
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                totals.merge(bucket.uid, bucket.rxBytes + bucket.txBytes, Long::plus)
            }
            stats.close()
        } catch (e: Exception) {
            return emptyMap()
        }
        return totals
    }

    /** Mobile data per app (and special buckets like tethering), sorted descending. */
    fun getPerAppMobileBytes(start: Long, end: Long): List<AppUsage> {
        val pm = context.packageManager
        return getPerAppBytesRaw(start, end).entries
            .filter { it.value > 0 }
            .map { (uid, bytes) ->
                var pkg: String? = null
                val label = when (uid) {
                    NetworkStats.Bucket.UID_TETHERING -> "Hotspot / Tethering"
                    NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
                    Process.SYSTEM_UID -> "Android System"
                    else -> {
                        val pkgs = pm.getPackagesForUid(uid)
                        if (pkgs.isNullOrEmpty()) {
                            "Unknown (uid $uid)"
                        } else {
                            pkg = pkgs[0]
                            try {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString()
                            } catch (e: Exception) {
                                pkgs[0]
                            }
                        }
                    }
                }
                AppUsage(uid, pkg, label, bytes)
            }
            .sortedByDescending { it.bytes }
    }
}
