package io.pelle.sak.backend

import io.pelle.sak.dns.model.AddressFamily
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.HealthState
import io.pelle.sak.backend.hosting.HostingHealth
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.backend.redirects.RedirectService
import io.pelle.sak.backend.redirects.RedirectsHealth
import io.pelle.sak.config.SakConfig
import io.pelle.sak.dns.DomainDnsCheck
import io.pelle.sak.dns.NameServer
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Path

class DomainsHealthTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val buckets by lazy { S3BucketService(database, testSecrets(database), rootDomain = "example.org") }

    private val redirects by lazy { RedirectService(database, buckets) }

    private val addresses = SakConfig.AddressesConfig(listOf("203.0.113.1"))

    // the domains that point to the server, every other one has no records
    private fun check(vararg pointing: String) =
        DomainDnsCheck(
            listOf(
                object : NameServer {
                    override val name = "1.1.1.1"

                    override fun addresses(
                        domain: String,
                        family: AddressFamily,
                    ) = if (domain in pointing && family == AddressFamily.IPV4) listOf(InetAddress.ofLiteral("203.0.113.1")) else emptyList<Inet4Address>()
                },
            ),
        )

    @Test
    fun `the buckets are healthy when all their domains and subdomains point to the server`() {
        buckets.create("site", listOf("site.example.com"))

        HostingHealth(buckets, check("site.example.com", "site.example.org"), addresses).check().state shouldBe HealthState.HEALTHY
        HostingHealth(buckets, check("site.example.com"), addresses).check().let {
            it.name shouldBe "Bucket domains"
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe "1 of 2 domains of the buckets do not point to the server"
            it.details.map { detail -> detail.name to detail.state } shouldBe
                listOf("site.example.com" to HealthState.HEALTHY, "site.example.org" to HealthState.UNHEALTHY)
        }
        HostingHealth(buckets, check(), SakConfig.AddressesConfig()).check().state shouldBe HealthState.UNKNOWN
    }

    @Test
    fun `the redirects are healthy when every redirected domain points to the server`() {
        redirects.create("old.example.com", RedirectTarget.Domain("example.net"))

        RedirectsHealth(redirects, check("old.example.com"), addresses).check().state shouldBe HealthState.HEALTHY
        RedirectsHealth(redirects, check(), addresses).check().let {
            it.name shouldBe "Redirect domains"
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe "1 of 1 domains of the redirects do not point to the server"
            it.details.single().message shouldBe "1.1.1.1 has no A, no AAAA"
        }
    }
}
