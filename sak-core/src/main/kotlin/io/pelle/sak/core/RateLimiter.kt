package io.pelle.sak.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

// at most maxEvents per key in a sliding window. At most maxKeys are kept, so many clients can not
// fill the heap: beyond them the key used longest ago goes, its count with it, and a new key is never
// refused for being new (SEC-41). Old events go when their key is asked again, and with a sweep of
// all keys at most once a minute
class RateLimiter(
    private val maxEvents: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    // the times of the events of each key, the oldest first; the keys in the order they were used
    // last, the one used longest ago first
    private val events = LinkedHashMap<String, ArrayDeque<Instant>>(16, 0.75f, true)
    private var swept: Instant? = null

    // when a key was dropped for a new one last told in the log
    private var warned: Instant? = null

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
                    if (events.size >= maxKeys) evict(now)
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

    // the key used longest ago goes for a new one; told in the log at most once a minute, without
    // the keys, which are addresses
    private fun evict(now: Instant) {
        events.remove(events.keys.first())
        if (warned?.let { Duration.between(it, now) >= SWEEP_EVERY } != false) {
            warned = now
            logger.warn { "a rate limit holds its most $maxKeys clients, the one asked longest ago is forgotten for a new one: many networks are asking" }
        }
    }

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
