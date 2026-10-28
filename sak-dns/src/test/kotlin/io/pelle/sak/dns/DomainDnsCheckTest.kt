package io.pelle.sak.dns

import io.pelle.sak.dns.model.AddressFamily
import io.pelle.sak.dns.model.DnsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.api.HealthDetail
import io.pelle.sak.api.HealthState
import org.junit.jupiter.api.Test
import java.net.InetAddress

// answers from a table: domain to the addresses of each family, a missing domain has none
class FakeNameServer(
    override val name: String,
    private val records: Map<String, List<String>> = emptyMap(),
    private val down: Boolean = false,
) : NameServer {
    override fun addresses(
        domain: String,
        family: AddressFamily,
    ): List<InetAddress> {
        if (down) throw DnsLookupException("$name did not answer for $domain")
        return records[domain].orEmpty().map(InetAddress::ofLiteral).filter { (it is java.net.Inet6Address) == (family == AddressFamily.IPV6) }
    }
}

private val IPV4 = listOf("203.0.113.1")
private val IPV6 = listOf("2001:db8::1")

class DomainDnsCheckTest {
    private fun check(vararg servers: NameServer) = DomainDnsCheck(servers.toList())

    @Test
    fun `asks every name server whether the domain points to the addresses of the server`() {
        val right = FakeNameServer("1.1.1.1", mapOf("site.example.org" to IPV4 + IPV6))
        val stale = FakeNameServer("8.8.8.8", mapOf("site.example.org" to listOf("203.0.113.9") + IPV6))
        val down = FakeNameServer("9.9.9.9", down = true)

        check(right, stale, down).check(listOf("site.example.org"), IPV4, IPV6).single().answers.map { it.server to it.state } shouldBe
            listOf("1.1.1.1" to DnsState.OK, "8.8.8.8" to DnsState.MISMATCH, "9.9.9.9" to DnsState.ERROR)
    }

    @Test
    fun `wants records of every family the server has, and none of the others`() {
        val server =
            FakeNameServer(
                "1.1.1.1",
                mapOf(
                    "both.example.org" to IPV4 + IPV6,
                    "ipv4.example.org" to IPV4,
                    "none.example.org" to emptyList(),
                    "elsewhere.example.org" to IPV4 + "2001:db8::9",
                ),
            )
        val states = { ipv6: List<String> -> check(server).check(listOf("both.example.org", "ipv4.example.org", "none.example.org", "elsewhere.example.org"), IPV4, ipv6).associate { it.domain to it.answers.single().state } }

        states(IPV6) shouldBe
            mapOf("both.example.org" to DnsState.OK, "ipv4.example.org" to DnsState.MISSING, "none.example.org" to DnsState.MISSING, "elsewhere.example.org" to DnsState.MISMATCH)
        // a server without ipv6, an aaaa record leads elsewhere
        states(emptyList()) shouldBe
            mapOf("both.example.org" to DnsState.MISMATCH, "ipv4.example.org" to DnsState.OK, "none.example.org" to DnsState.MISSING, "elsewhere.example.org" to DnsState.MISMATCH)
    }

    @Test
    fun `is healthy when every name server that answers has the addresses of the server`() {
        val right = FakeNameServer("1.1.1.1", mapOf("a.example.org" to IPV4 + IPV6, "b.example.org" to IPV4 + IPV6))
        val down = FakeNameServer("9.9.9.9", down = true)

        DomainsHealth("Bucket domains", "buckets", { listOf("b.example.org", "a.example.org") }, check(right), IPV4, IPV6).check().let {
            it.state shouldBe HealthState.HEALTHY
            it.message shouldBe "the 2 domains of the buckets point to the server"
            it.details shouldBe listOf(HealthDetail("a.example.org", HealthState.HEALTHY), HealthDetail("b.example.org", HealthState.HEALTHY))
        }
        DomainsHealth("Bucket domains", "buckets", { listOf("a.example.org") }, check(right, down), IPV4, IPV6).check().let {
            it.state shouldBe HealthState.HEALTHY
            it.details shouldBe listOf(HealthDetail("a.example.org", HealthState.HEALTHY, "no answer from 9.9.9.9"))
        }
        DomainsHealth("Bucket domains", "buckets", { emptyList() }, check(right), IPV4, IPV6).check().state shouldBe HealthState.HEALTHY
    }

    @Test
    fun `is unhealthy when a name server answers with no or other addresses, and tells which`() {
        val stale = FakeNameServer("8.8.8.8", mapOf("a.example.org" to listOf("203.0.113.9"), "c.example.org" to IPV4))

        DomainsHealth("Redirect domains", "redirects", { listOf("a.example.org", "b.example.org", "c.example.org") }, check(stale), IPV4, emptyList()).check().let {
            it.name shouldBe "Redirect domains"
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe "2 of 3 domains of the redirects do not point to the server"
            it.details shouldBe
                listOf(
                    HealthDetail("a.example.org", HealthState.UNHEALTHY, "8.8.8.8 has A 203.0.113.9, no AAAA"),
                    HealthDetail("b.example.org", HealthState.UNHEALTHY, "8.8.8.8 has no A, no AAAA"),
                    HealthDetail("c.example.org", HealthState.HEALTHY),
                )
        }
    }

    @Test
    fun `is unknown without addresses of the server or answers`() {
        val right = FakeNameServer("1.1.1.1", mapOf("a.example.org" to IPV4))
        val down = FakeNameServer("9.9.9.9", down = true)

        DomainsHealth("Bucket domains", "buckets", { listOf("a.example.org") }, check(right), emptyList(), emptyList()).check().state shouldBe HealthState.UNKNOWN
        DomainsHealth("Bucket domains", "buckets", { listOf("a.example.org") }, check(down), IPV4, emptyList()).check().let {
            it.state shouldBe HealthState.UNKNOWN
            it.message shouldBe "no name server answered for 1 of 1 domains of the buckets"
            it.details shouldBe listOf(HealthDetail("a.example.org", HealthState.UNKNOWN, "no name server answered"))
        }
        DomainsHealth("Bucket domains", "buckets", { error("the database is gone") }, check(right), IPV4, emptyList()).check().let {
            it.state shouldBe HealthState.UNKNOWN
            it.message!! shouldContain "could not be checked"
        }
    }
}
