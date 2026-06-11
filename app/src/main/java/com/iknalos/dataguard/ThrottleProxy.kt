package com.iknalos.dataguard

import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal HTTP proxy (CONNECT for HTTPS, absolute-form for plain HTTP) that
 * pushes every byte through a shared [TokenBucket].
 *
 * Two ways it is used:
 *  - On localhost, adopted device-wide by [ThrottleVpnService] via
 *    VpnService.Builder.setHttpProxy() to cap the phone's own apps.
 *  - On 0.0.0.0, exposed to hotspot clients by [HotspotProxyService] so
 *    tethered devices that point their Wi-Fi proxy here are capped too.
 *
 * When a [vpn] is supplied, upstream sockets are protect()-ed so they bypass
 * the VPN; for the hotspot case [vpn] is null and sockets route normally.
 */
class ThrottleProxy(
    private val vpn: VpnService?,
    private val bucket: TokenBucket,
    private val bindHost: String = "127.0.0.1",
    private val bindPort: Int = 0
) : Closeable {

    private val server = ServerSocket()
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var closed = false

    val port: Int get() = server.localPort

    fun start() {
        server.bind(InetSocketAddress(bindHost, bindPort))
        Thread({ acceptLoop() }, "proxy-accept").start()
    }

    override fun close() {
        closed = true
        try { server.close() } catch (e: Exception) { }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try { server.accept() } catch (e: Exception) { break }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val clientIn = client.getInputStream()
            val headerBytes = readHeaders(clientIn) ?: run { client.close(); return }
            val firstLine = String(headerBytes, Charsets.ISO_8859_1).substringBefore("\r\n")
            val parts = firstLine.split(" ")
            if (parts.size < 3) { client.close(); return }

            val isConnect = parts[0].equals("CONNECT", ignoreCase = true)
            val target = if (isConnect) {
                parseHostPort(parts[1], 443)
            } else {
                val afterScheme = parts[1].substringAfter("://", "")
                if (afterScheme.isEmpty()) { client.close(); return }
                parseHostPort(afterScheme.substringBefore("/"), 80)
            } ?: run { client.close(); return }

            upstream = Socket()
            vpn?.protect(upstream)
            upstream.connect(InetSocketAddress(target.first, target.second), 15000)
            upstream.tcpNoDelay = true

            if (isConnect) {
                client.getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            } else {
                // Origin servers must accept absolute-form (RFC 7230 5.3.2),
                // so forward the request bytes untouched.
                upstream.getOutputStream().write(headerBytes)
            }

            val up = upstream
            val uploader = Thread {
                pump(clientIn, up.getOutputStream())
                closeQuietly(up)
            }
            uploader.start()
            pump(up.getInputStream(), client.getOutputStream())
            closeQuietly(client)
            closeQuietly(up)
            uploader.join(2000)
        } catch (e: Exception) {
            closeQuietly(client)
            upstream?.let { closeQuietly(it) }
        }
    }

    /** Reads bytes one at a time until the \r\n\r\n header terminator. */
    private fun readHeaders(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 65536) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> return buf.toByteArray()
                else -> 0
            }
        }
        return null
    }

    private fun parseHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
        return if (value.startsWith("[")) {
            // IPv6 literal, e.g. [::1]:443
            val end = value.indexOf(']')
            if (end < 0) return null
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            host to port
        } else {
            val host = value.substringBefore(":")
            if (host.isEmpty()) return null
            val port = value.substringAfter(":", "").toIntOrNull() ?: defaultPort
            host to port
        }
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                bucket.acquire(n)
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (e: Exception) {
            // Connection closed; pump ends.
        }
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (e: Exception) { }
    }
}
