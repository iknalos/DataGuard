package com.iknalos.dataguard

import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which [TokenBucket] a proxied connection should use. If the owning
 * app has a per-app cap configured, that app's bucket is used; otherwise the
 * shared global bucket applies. The owning app is resolved from the loopback
 * connection's 5-tuple via ConnectivityManager.getConnectionOwnerUid (API 29+).
 */
class BucketRegistry(private val context: Context) {

    val globalBucket = TokenBucket(0)
    private val appCaps = HashMap<String, Int>()        // package -> Mbps
    private val perUid = ConcurrentHashMap<Int, TokenBucket>()
    private val uidPkg = ConcurrentHashMap<Int, String>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun update(globalMbps: Int, caps: Map<String, Int>) {
        globalBucket.bytesPerSec = globalMbps * 125_000L
        appCaps.clear()
        appCaps.putAll(caps)
        perUid.clear()
        uidPkg.clear()
    }

    fun bucketForSocket(client: Socket): TokenBucket {
        val uid = ownerUid(client)
        if (uid >= 0) {
            val pkg = pkgFor(uid)
            val capMbps = if (pkg != null) appCaps[pkg] else null
            if (capMbps != null) {
                return perUid.getOrPut(uid) { TokenBucket(capMbps * 125_000L) }
                    .also { it.bytesPerSec = capMbps * 125_000L }
            }
        }
        return globalBucket
    }

    private fun ownerUid(client: Socket): Int = try {
        cm?.getConnectionOwnerUid(
            OsConstants.IPPROTO_TCP,
            client.remoteSocketAddress as InetSocketAddress, // app's local end
            client.localSocketAddress as InetSocketAddress   // proxy = app's remote end
        ) ?: -1
    } catch (e: Exception) {
        -1
    }

    private fun pkgFor(uid: Int): String? {
        val cached = uidPkg.getOrPut(uid) {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
        }
        return cached.ifEmpty { null }
    }
}
