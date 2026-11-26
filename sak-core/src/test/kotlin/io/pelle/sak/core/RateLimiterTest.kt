package io.pelle.sak.core

import io.pelle.sak.core.testing.MutableClock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class RateLimiterTest {
    // many clients do not fill the heap, and a new one is never refused for being new (SEC-41): beyond
    // the most keys the one used longest ago goes, the counts of the others stay
    @Test
    fun `keeps at most max keys, a new one beyond them takes the place of the one used longest ago`() {
        val clock = MutableClock()
        val limiter = RateLimiter(2, Duration.ofHours(1), clock, maxKeys = 3)

        (1..3).forEach { limiter.tryAcquire("client $it") shouldBe true }
        // client 1 used again: client 2 is the one used longest ago
        limiter.tryAcquire("client 1") shouldBe true
        limiter.tryAcquire("client 4") shouldBe true
        limiter.keys() shouldBe 3
        // client 1 kept its count, its limit of 2 is reached; client 2 was forgotten, it starts again
        limiter.tryAcquire("client 1") shouldBe false
        limiter.tryAcquire("client 2") shouldBe true
        limiter.keys() shouldBe 3

        // many new ones, the most keys stay
        (5..100).forEach { limiter.tryAcquire("client $it") shouldBe true }
        limiter.keys() shouldBe 3

        clock.advance(Duration.ofHours(2))
        limiter.tryAcquire("client 4") shouldBe true
        limiter.keys() shouldBe 1
    }

    // a host has a /64 of IPv6 addresses, it counts as one client
    @Test
    fun `exhausted tells a key used up, without counting an event`() {
        val clock = MutableClock()
        val limiter = RateLimiter(2, Duration.ofHours(1), clock)
        limiter.exhausted("a") shouldBe false
        limiter.tryAcquire("a") shouldBe true
        limiter.exhausted("a") shouldBe false
        limiter.tryAcquire("a") shouldBe true
        limiter.exhausted("a") shouldBe true
        limiter.exhausted("b") shouldBe false
        clock.advance(Duration.ofHours(2))
        limiter.exhausted("a") shouldBe false
    }

    @Test
    fun `counts an IPv6 client by its network of 64 bits, an IPv4 client by its address`() {
        clientNetwork("2001:db8:1:2:aaaa:bbbb:cccc:dddd") shouldBe "2001:db8:1:2::/64"
        clientNetwork("2001:db8:1:2::1") shouldBe "2001:db8:1:2::/64"
        clientNetwork("2001:db8::1") shouldBe "2001:db8:0:0::/64"
        clientNetwork("192.0.2.17") shouldBe "192.0.2.17"
        clientNetwork("::ffff:192.0.2.17") shouldBe "192.0.2.17"
        clientNetwork("not an address") shouldBe "not an address"
    }

    @Test
    fun `an event given back counts no more, of a key without events nothing happens (SEC-40)`() {
        val limiter = RateLimiter(2, Duration.ofHours(1), MutableClock())
        limiter.tryAcquire("a") shouldBe true
        limiter.tryAcquire("a") shouldBe true
        limiter.tryAcquire("a") shouldBe false
        limiter.release("a")
        limiter.tryAcquire("a") shouldBe true
        limiter.release("b")
        limiter.release("a")
        limiter.release("a")
        limiter.keys() shouldBe 0
    }
}

