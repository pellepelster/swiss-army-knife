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
}
