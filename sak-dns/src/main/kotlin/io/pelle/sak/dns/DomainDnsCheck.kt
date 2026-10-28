package io.pelle.sak.dns

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetAddress
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

enum class DnsState {
    // the records of each family are all addresses of the server
    OK,

    // there are no records of a family the server has addresses of
    MISSING,

    // a record is no address of the server, e.g. an aaaa record although the server has no ipv6
    MISMATCH,

    // the name server did not answer
    ERROR,
}

// what one name server answers for a domain
data class DnsAnswer(
    val server: String,
    val state: DnsState,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val error: String? = null,
)

data class DomainDnsResult(
    val domain: String,
    val answers: List<DnsAnswer>,
)

// whether domains point to the addresses of a server, asked of every name server: a family the
// server has addresses of has records, all of them its addresses; a family it has none of has no
// records, they would lead elsewhere. Every name server is asked at once, the domains as well
class DomainDnsCheck(
    private val servers: List<NameServer>,
) {
    fun check(
        domains: Collection<String>,
        ipv4: Collection<String>,
        ipv6: Collection<String>,
    ): List<DomainDnsResult> {
        val expected = mapOf(AddressFamily.IPV4 to ipv4.map(InetAddress::ofLiteral).toSet(), AddressFamily.IPV6 to ipv6.map(InetAddress::ofLiteral).toSet())
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            domains
                .map { domain -> domain to servers.map { server -> executor.submit<DnsAnswer> { answer(server, domain, expected) } } }
                .map { (domain, answers) -> DomainDnsResult(domain, answers.map { it.get() }) }
        }
    }

    private fun answer(
        server: NameServer,
        domain: String,
        expected: Map<AddressFamily, Set<InetAddress>>,
    ): DnsAnswer {
        val found =
            try {
                AddressFamily.entries.associateWith { server.addresses(domain, it) }
            } catch (e: DnsLookupException) {
                logger.info { "the addresses of '$domain' could not be read from ${server.name}: ${e.message}" }
                return DnsAnswer(server.name, DnsState.ERROR, error = e.message)
            }
        val states =
            AddressFamily.entries.map { family ->
                val records = found.getValue(family)
                val addresses = expected.getValue(family)
                when {
                    records.any { it !in addresses } -> DnsState.MISMATCH
                    addresses.isNotEmpty() && records.isEmpty() -> DnsState.MISSING
                    else -> DnsState.OK
                }
            }
        return DnsAnswer(
            server.name,
            states.maxBy { it.ordinal },
            found.getValue(AddressFamily.IPV4).map { it.hostAddress },
            found.getValue(AddressFamily.IPV6).map { it.hostAddress },
        )
    }
}
