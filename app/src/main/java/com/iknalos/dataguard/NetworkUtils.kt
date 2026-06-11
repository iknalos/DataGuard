package com.iknalos.dataguard

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Best guess at the IPv4 address hotspot clients should target. Android's
     * tether interface is usually named ap0, swlan0 or wlan1 and sits on a
     * 192.168.x.1-style subnet. Falls back to any site-local IPv4 if the
     * interface name isn't recognised.
     */
    fun hotspotAddress(): String? {
        val candidates = ArrayList<Pair<String, String>>() // name to ip
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        candidates.add(nif.name.lowercase() to addr.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (candidates.isEmpty()) return null
        // Prefer interfaces that look like a soft-AP / tether interface.
        val apMarkers = listOf("ap", "swlan", "wlan1", "softap", "tether", "rndis")
        return candidates.firstOrNull { (name, _) -> apMarkers.any { name.contains(it) } }?.second
            ?: candidates.firstOrNull { (_, ip) -> ip.startsWith("192.168.") }?.second
            ?: candidates.first().second
    }
}
