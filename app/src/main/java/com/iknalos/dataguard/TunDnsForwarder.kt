package com.iknalos.dataguard

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Reads packets from the TUN device, relays DNS queries (UDP port 53) to a
 * real resolver through a protected socket, and writes the answers back.
 * Everything else captured by the TUN is dropped: TCP rides the system HTTP
 * proxy instead, and dropping UDP (QUIC) forces video apps to fall back to
 * throttled TCP.
 */
class TunDnsForwarder(
    private val vpn: VpnService,
    tun: ParcelFileDescriptor
) {
    private val pool = Executors.newCachedThreadPool()
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Object()

    @Volatile
    private var running = true

    fun start() {
        Thread({ loop() }, "tun-dns").start()
    }

    fun stop() {
        running = false
        pool.shutdownNow()
    }

    private fun loop() {
        val buf = ByteArray(32767)
        while (running) {
            val len = try { input.read(buf) } catch (e: Exception) { break }
            if (len <= 0) continue
            if ((buf[0].toInt() ushr 4) != 4) continue           // IPv4 only
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (len < ihl + 8) continue
            if (buf[9].toInt() != 17) continue                    // UDP only
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or
                    (buf[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue
            val packet = buf.copyOf(len)
            pool.execute { relay(packet, ihl) }
        }
    }

    private fun relay(query: ByteArray, ihl: Int) {
        try {
            val payload = query.copyOfRange(ihl + 8, query.size)
            val socket = DatagramSocket()
            vpn.protect(socket)
            socket.use {
                it.soTimeout = 5000
                it.send(
                    DatagramPacket(
                        payload, payload.size,
                        InetSocketAddress(InetAddress.getByName(RESOLVER), 53)
                    )
                )
                val respBuf = ByteArray(4096)
                val resp = DatagramPacket(respBuf, respBuf.size)
                it.receive(resp)
                val answer = buildUdpResponse(query, ihl, respBuf, resp.length)
                synchronized(writeLock) { output.write(answer) }
            }
        } catch (e: Exception) {
            // Query timed out or TUN closed; the app will retry DNS itself.
        }
    }

    /** Builds an IPv4+UDP packet carrying [payload] back to the querying app. */
    private fun buildUdpResponse(
        query: ByteArray,
        ihl: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 20 + 8 + payloadLen
        val out = ByteArray(totalLen)
        out[0] = 0x45.toByte()                    // IPv4, header length 20
        out[2] = (totalLen ushr 8).toByte()
        out[3] = totalLen.toByte()
        out[6] = 0x40.toByte()                    // don't fragment
        out[8] = 64                               // TTL
        out[9] = 17                               // UDP
        System.arraycopy(query, 16, out, 12, 4)   // src = original destination
        System.arraycopy(query, 12, out, 16, 4)   // dst = original source
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        val checksum = sum.inv() and 0xFFFF
        out[10] = (checksum ushr 8).toByte()
        out[11] = checksum.toByte()
        out[20] = 0
        out[21] = 53                              // src port 53
        out[22] = query[ihl]                      // dst port = original src port
        out[23] = query[ihl + 1]
        val udpLen = 8 + payloadLen
        out[24] = (udpLen ushr 8).toByte()
        out[25] = udpLen.toByte()
        // UDP checksum 0 = "not computed", valid for UDP over IPv4.
        System.arraycopy(payload, 0, out, 28, payloadLen)
        return out
    }

    companion object {
        private const val RESOLVER = "1.1.1.1"
    }
}
