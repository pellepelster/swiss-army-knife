package io.pelle.sak.dns

import io.pelle.sak.dns.model.DnsAnswer
import io.pelle.sak.dns.model.DnsState
import io.pelle.sak.dns.model.DomainDnsResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.Health
import io.pelle.sak.api.HealthDetail
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
        details: List<HealthDetail> = emptyList(),
    ) = HealthStatus(name, state, message, details)

    private fun checked(domains: Collection<String>): HealthStatus {
        if (ipv4.isEmpty() && ipv6.isEmpty()) {
            return status(HealthState.UNKNOWN, "the addresses of the server are not configured")
        }
        if (domains.isEmpty()) {
            return status(HealthState.HEALTHY, "the $what have no domains")
        }
        val details = check.check(domains.distinct().sorted(), ipv4, ipv6).map { it.detail() }
        val count = { state: HealthState -> details.count { it.state == state } }
        return when {
            count(HealthState.UNHEALTHY) > 0 ->
                status(HealthState.UNHEALTHY, "${count(HealthState.UNHEALTHY)} of ${details.size} domains of the $what do not point to the server", details)
            count(HealthState.UNKNOWN) > 0 ->
                status(HealthState.UNKNOWN, "no name server answered for ${count(HealthState.UNKNOWN)} of ${details.size} domains of the $what", details)
            else -> status(HealthState.HEALTHY, "the ${details.size} domains of the $what point to the server", details)
        }
    }
}

// unhealthy when a name server answers with no or other addresses, unknown when none answers;
// the message tells what the name servers answered that is wrong, or which did not answer
private fun DomainDnsResult.detail(): HealthDetail {
    val wrong = answers.filter { it.state == DnsState.MISSING || it.state == DnsState.MISMATCH }
    val unanswered = answers.filter { it.state == DnsState.ERROR }.map { it.server }
    return when {
        wrong.isNotEmpty() -> HealthDetail(domain, HealthState.UNHEALTHY, wrong.joinToString("; ") { it.describe() })
        unanswered.size == answers.size -> HealthDetail(domain, HealthState.UNKNOWN, "no name server answered")
        unanswered.isNotEmpty() -> HealthDetail(domain, HealthState.HEALTHY, "no answer from ${unanswered.joinToString(", ")}")
        else -> HealthDetail(domain, HealthState.HEALTHY)
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
