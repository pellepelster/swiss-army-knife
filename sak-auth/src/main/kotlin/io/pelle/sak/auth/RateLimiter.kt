package io.pelle.sak.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant

// allows at most maxEvents per key within a sliding window
class RateLimiter(
    private val maxEvents: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val events = HashMap<String, ArrayDeque<Instant>>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.instant()
        val since = now.minus(window)
        events.values.forEach { timestamps -> timestamps.removeAll { !it.isAfter(since) } }
        events.values.removeIf { it.isEmpty() }

        val timestamps = events.getOrPut(key) { ArrayDeque() }
        if (timestamps.size >= maxEvents) {
            return false
        }
        timestamps.addLast(now)
        return true
    }
}
