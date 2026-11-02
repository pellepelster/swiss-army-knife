package io.pelle.sak.backend.info

import io.kotest.matchers.shouldBe
import io.pelle.sak.config.DnsMode
import io.pelle.sak.config.SakConfig
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SakWellKnownTest {
    private fun config(dnsMode: DnsMode) = SakConfig(dataDir = Path.of("/data"), name = "alpha", rootDomain = "example.org", dnsMode = dnsMode)

    @Test
    fun `the SAK address leads to the api of its base domain, of the dns mode`() {
        apiAddress(config(DnsMode.DOMAIN), "https", "sak.example.org", 443) shouldBe "https://api.example.org/v1"
        apiAddress(config(DnsMode.SUBDOMAIN), "https", "sak.alpha.example.org", 443) shouldBe "https://api.alpha.example.org/v1"
    }

    @Test
    fun `any other address leads to the api of its own origin`() {
        // the dev server of local:run, from the emulator
        apiAddress(config(DnsMode.DOMAIN), "http", "10.0.2.2", 4200) shouldBe "http://10.0.2.2:4200/api/v1"
        apiAddress(config(DnsMode.DOMAIN), "http", "localhost", 80) shouldBe "http://localhost/api/v1"
        apiAddress(config(DnsMode.DOMAIN), "https", "other.example.org", 443) shouldBe "https://other.example.org/api/v1"
    }
}
