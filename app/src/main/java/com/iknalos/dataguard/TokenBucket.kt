package com.iknalos.dataguard

/**
 * Global token-bucket rate limiter shared by all proxy connections, so the
 * cap applies to total throughput rather than per-connection.
 */
class TokenBucket(initialBytesPerSec: Long) {
    @Volatile
    var bytesPerSec: Long = initialBytesPerSec

    private var tokens = 0.0
    private var lastRefill = System.nanoTime()
    private val lock = Object()

    /** Blocks until [n] bytes worth of tokens are available. */
    fun acquire(n: Int) {
        synchronized(lock) {
            while (true) {
                val rate = bytesPerSec.toDouble()
                if (rate <= 0) return // unlimited
                val now = System.nanoTime()
                // Allow up to half a second of burst.
                tokens = minOf(tokens + (now - lastRefill) / 1e9 * rate, rate / 2)
                lastRefill = now
                if (tokens >= n) {
                    tokens -= n
                    return
                }
                val waitMs = (((n - tokens) / rate) * 1000).toLong().coerceIn(1, 1000)
                try {
                    lock.wait(waitMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }
}
