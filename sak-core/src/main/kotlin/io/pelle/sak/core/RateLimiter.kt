package io.pelle.sak.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

// at most maxEvents per key in a sliding window. A new key beyond maxKeys is refused, so many
// clients can not fill the heap. Old events go when their key is asked again, and with a sweep of
// all keys at most once a minute
class RateLimiter(
    private val maxEvents: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    // the times of the events of each key, the oldest first
    private val events = HashMap<String, ArrayDeque<Instant>>()
    private var swept: Instant? = null

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.instant()
        val since = now.minus(window)
        if (swept?.let { Duration.between(it, now) >= SWEEP_EVERY } != false) {
            sweep(since)
            swept = now
        }
        val timestamps =
            events[key]?.also { dropBefore(it, since) }
                ?: run {
                    if (events.size >= maxKeys) sweep(since)
                    if (events.size >= maxKeys) return false
                    ArrayDeque<Instant>().also { events[key] = it }
                }
        if (timestamps.size >= maxEvents) {
            return false
        }
        timestamps.addLast(now)
        return true
    }

    // whether the key has used up its events in the window, without counting one more: a guard
    // that refuses before it checks, e.g. a login, asks this first
    @Synchronized
    fun exhausted(key: String): Boolean {
        val timestamps = events[key] ?: return false
        dropBefore(timestamps, clock.instant().minus(window))
        return timestamps.size >= maxEvents
    }

    // gives the newest event of the key back, e.g. an attempt that turned out to be no failure: counted
    // first, so attempts at once can not pass the limit together, given back once it succeeded (SEC-40)
    @Synchronized
    fun release(key: String) {
        val timestamps = events[key] ?: return
        timestamps.removeLastOrNull()
        if (timestamps.isEmpty()) events.remove(key)
    }

    // the number of keys kept, for the tests
    @Synchronized
    internal fun keys(): Int = events.size

    private fun sweep(since: Instant) {
        events.values.forEach { dropBefore(it, since) }
        events.values.removeIf { it.isEmpty() }
    }

    private fun dropBefore(
        timestamps: ArrayDeque<Instant>,
        since: Instant,
    ) {
        while (timestamps.firstOrNull()?.isAfter(since) == false) timestamps.removeFirst()
    }

    companion object {
        // far more clients than a household has in an hour, a few MB at most
        const val DEFAULT_MAX_KEYS = 10_000

        private val SWEEP_EVERY: Duration = Duration.ofMinutes(1)
    }
}
