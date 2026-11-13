package io.pelle.sak.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class RateLimiterTest {
    // many clients do not fill the heap, a new one beyond the most keys is refused
    @Test
    fun `keeps at most max keys and refuses a new one beyond them until old ones passed the window`() {
        val clock = MutableClock()
        val limiter = RateLimiter(5, Duration.ofHours(1), clock, maxKeys = 3)

        (1..3).forEach { limiter.tryAcquire("client $it") shouldBe true }
        limiter.tryAcquire("client 4") shouldBe false
        // a client it knows goes on
        limiter.tryAcquire("client 1") shouldBe true
        limiter.keys() shouldBe 3

        clock.advance(Duration.ofHours(2))
        limiter.tryAcquire("client 4") shouldBe true
        limiter.keys() shouldBe 1
    }

    // a host has a /64 of IPv6 addresses, it counts as one client
    @Test
    fun `counts an IPv6 client by its network of 64 bits, an IPv4 client by its address`() {
        clientNetwork("2001:db8:1:2:aaaa:bbbb:cccc:dddd") shouldBe "2001:db8:1:2::/64"
        clientNetwork("2001:db8:1:2::1") shouldBe "2001:db8:1:2::/64"
        clientNetwork("2001:db8::1") shouldBe "2001:db8:0:0::/64"
        clientNetwork("192.0.2.17") shouldBe "192.0.2.17"
        clientNetwork("::ffff:192.0.2.17") shouldBe "192.0.2.17"
        clientNetwork("not an address") shouldBe "not an address"
    }
}
