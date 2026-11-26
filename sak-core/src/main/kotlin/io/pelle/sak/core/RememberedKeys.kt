package io.pelle.sak.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

// keys remembered for a time after they were seen last, e.g. the networks the admin signed in from
// (SEC-40); at most maxKeys, the one seen longest ago goes first. In memory, a restart forgets them
class RememberedKeys(
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = 1_000,
) {
    // by the time each was seen last, in the order they were seen
    private val seen = LinkedHashMap<String, Instant>()

    @Synchronized
    fun remember(key: String) {
        seen.remove(key)
        seen[key] = clock.instant()
        while (seen.size > maxKeys) seen.remove(seen.keys.first())
    }

    @Synchronized
    fun contains(key: String): Boolean {
        val at = seen[key] ?: return false
        if (at.plus(ttl).isAfter(clock.instant())) return true
        seen.remove(key)
        return false
    }
}
