package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class LogAddressTest {
    @Test
    fun `an address is logged as the start of its hash, the same for every spelling of its case`() {
        // printf '%s' 'anna@example.org' | sha256sum
        logAddress("anna@example.org") shouldBe "user:a3bf86238a1c"
        logAddress(" Anna@Example.org ") shouldBe logAddress("anna@example.org")
        logAddress("anna@example.org") shouldNotContain "anna"
    }

    @Test
    fun `a client is logged by its network only, like caddy masks it`() {
        logClient("203.0.113.7") shouldBe "203.0.113.0"
        logClient("2001:db8:1:2:3:4:5:6") shouldBe "2001:db8:1:2::"
        logClient("2001:db8::1") shouldBe "2001:db8:0:0::"
        logClient("[2001:db8:1:2::7]") shouldBe "2001:db8:1:2::"
        logClient("not.an.address") shouldBe "client:unknown"
    }

    @Test
    fun `a text names no e-mail address and no whole ipv4 address`() {
        logText("550 5.1.1 <anna@example.org>: Recipient address rejected from 203.0.113.7") shouldBe
            "550 5.1.1 <user:a3bf86238a1c>: Recipient address rejected from 203.0.113.0"
    }

    @Test
    fun `a logged exception keeps its class, stack and causes, its messages name nobody`() {
        val cause = IllegalStateException("no route to 203.0.113.7")
        val original = RuntimeException("could not send to anna@example.org", cause)
        val logged = logCause(original)

        logged.toString() shouldBe "java.lang.RuntimeException: could not send to user:a3bf86238a1c"
        logged.stackTrace.toList() shouldBe original.stackTrace.toList()
        logged.cause.toString() shouldBe "java.lang.IllegalStateException: no route to 203.0.113.0"
        logged.stackTraceToString() shouldNotContain "anna"
        logged.stackTraceToString() shouldNotContain "203.0.113.7"
    }
}
