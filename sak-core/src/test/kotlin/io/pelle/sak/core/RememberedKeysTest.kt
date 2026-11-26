package io.pelle.sak.core

import io.kotest.matchers.shouldBe
import io.pelle.sak.core.testing.MutableClock
import org.junit.jupiter.api.Test
import java.time.Duration

class RememberedKeysTest {
    @Test
    fun `a key is remembered for its time from when it was seen last, the oldest goes beyond the most (SEC-40)`() {
        val clock = MutableClock()
        val keys = RememberedKeys(Duration.ofDays(30), clock, maxKeys = 2)
        keys.contains("a") shouldBe false
        keys.remember("a")
        clock.advance(Duration.ofDays(20))
        keys.contains("a") shouldBe true
        // seen again: 30 days from now
        keys.remember("a")
        clock.advance(Duration.ofDays(20))
        keys.contains("a") shouldBe true
        clock.advance(Duration.ofDays(10))
        keys.contains("a") shouldBe false

        keys.remember("b")
        keys.remember("c")
        keys.remember("d")
        keys.contains("b") shouldBe false
        keys.contains("c") shouldBe true
        keys.contains("d") shouldBe true
    }
}
