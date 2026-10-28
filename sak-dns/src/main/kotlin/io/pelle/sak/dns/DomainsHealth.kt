package io.pelle.sak.dns

import io.pelle.sak.dns.model.DnsAnswer
import io.pelle.sak.dns.model.DnsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.Health
import io.pelle.sak.api.HealthState
import io.pelle.sak.api.HealthStatus

private val logger = KotlinLogging.logger {}

// the health of domains that must point to the addresses of the server, e.g. the ones the buckets
// are served at: unhealthy when a name server answers a domain with no or other addresses, unknown
// without addresses of the server or when no name server answers for a domain, healthy otherwise
open class DomainsHealth(
    // what is checked as the user sees it, e.g. Bucket domains
    private val name: String,
    // what the domains are for in the messages, e.g. buckets
    private val what: String,
    // asked on every check, the domains change
    private val domains: () -> Collection<String>,
    private val check: DomainDnsCheck,
    private val ipv4: Collection<String>,
    private val ipv6: Collection<String>,
) : Health {
    override fun check(): HealthStatus =
        try {
            checked(domains())
        } catch (e: Exception) {
            logger.warn(e) { "the domains of the $what could not be checked" }
            status(HealthState.UNKNOWN, "the domains of the $what could not be checked")
        }

    private fun status(
        state: HealthState,
        message: String,
    ) = HealthStatus(name, state, message)

    private fun checked(domains: Collection<String>): HealthStatus {
        if (ipv4.isEmpty() && ipv6.isEmpty()) {
            return status(HealthState.UNKNOWN, "the addresses of the server are not configured")
        }
        if (domains.isEmpty()) {
            return status(HealthState.HEALTHY, "the $what have no domains")
        }
        val results = check.check(domains.distinct().sorted(), ipv4, ipv6)
        val wrong =
            results.flatMap { result ->
                result.answers.filter { it.state == DnsState.MISSING || it.state == DnsState.MISMATCH }.map { "${result.domain}: ${it.describe()}" }
            }
        if (wrong.isNotEmpty()) {
            return status(HealthState.UNHEALTHY, wrong.joinToString("; "))
        }
        val unanswered = results.filter { result -> result.answers.all { it.state == DnsState.ERROR } }.map { it.domain }
        if (unanswered.isNotEmpty()) {
            return status(HealthState.UNKNOWN, "no name server answered for ${unanswered.joinToString(", ")}")
        }
        val errors = results.flatMap { result -> result.answers.filter { it.state == DnsState.ERROR }.map { "${it.server} for ${result.domain}" } }
        val points = "the ${results.size} domains of the $what point to the server"
        return status(HealthState.HEALTHY, if (errors.isEmpty()) points else "$points, no answer from ${errors.joinToString(", ")}")
    }
}

// e.g. "8.8.8.8 has A 203.0.113.9, no AAAA"
private fun DnsAnswer.describe(): String {
    fun records(
        type: String,
        addresses: List<String>,
    ) = if (addresses.isEmpty()) "no $type" else "$type ${addresses.joinToString(" ")}"
    return "$server has ${records("A", ipv4)}, ${records("AAAA", ipv6)}"
}
