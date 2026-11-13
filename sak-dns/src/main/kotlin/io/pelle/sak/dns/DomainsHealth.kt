package io.pelle.sak.dns

import io.pelle.sak.api.logCause
import io.pelle.sak.dns.model.DnsAnswer
import io.pelle.sak.dns.model.DnsState
import io.pelle.sak.dns.model.DomainDnsResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.Health
import io.pelle.sak.api.HealthDetail
import io.pelle.sak.api.HealthState
import io.pelle.sak.api.HealthStatus
import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Joined
import io.pelle.sak.api.Text

private val logger = KotlinLogging.logger {}

// the health of domains that must point to the addresses of the server, e.g. the ones the buckets
// are served at: unhealthy when a name server answers a domain with no or other addresses, unknown
// without addresses of the server or when no name server answers for a domain, healthy otherwise
open class DomainsHealth(
    // what is checked as the user sees it, e.g. Bucket domains
    private val name: Text,
    // what the domains are for in the messages, e.g. buckets
    private val what: Text,
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
            logger.warn(logCause(e)) { "the domains of the $what could not be checked" }
            status(HealthState.UNKNOWN, Text(DnsTexts.NOT_CHECKED, "what" to what))
        }

    private fun status(
        state: HealthState,
        message: Text,
        details: List<HealthDetail> = emptyList(),
    ) = HealthStatus(name, state, message, details)

    private fun checked(domains: Collection<String>): HealthStatus {
        if (ipv4.isEmpty() && ipv6.isEmpty()) {
            return status(HealthState.UNKNOWN, Text(DnsTexts.NO_ADDRESSES))
        }
        if (domains.isEmpty()) {
            return status(HealthState.HEALTHY, Text(DnsTexts.NO_DOMAINS, "what" to what))
        }
        val details = check.check(domains.distinct().sorted(), ipv4, ipv6).map { it.detail() }
        val count = { state: HealthState -> details.count { it.state == state } }
        return when {
            count(HealthState.UNHEALTHY) > 0 ->
                status(
                    HealthState.UNHEALTHY,
                    Text(DnsTexts.WRONG_DOMAINS, "count" to count(HealthState.UNHEALTHY), "total" to details.size, "what" to what),
                    details,
                )
            count(HealthState.UNKNOWN) > 0 ->
                status(
                    HealthState.UNKNOWN,
                    Text(DnsTexts.UNANSWERED_DOMAINS, "count" to count(HealthState.UNKNOWN), "total" to details.size, "what" to what),
                    details,
                )
            else -> status(HealthState.HEALTHY, Text(DnsTexts.RIGHT_DOMAINS, "total" to details.size, "what" to what), details)
        }
    }
}

// unhealthy when a name server answers with no or other addresses, unknown when none answers;
// the message tells what the name servers answered that is wrong, or which did not answer
private fun DomainDnsResult.detail(): HealthDetail {
    val wrong = answers.filter { it.state == DnsState.MISSING || it.state == DnsState.MISMATCH }
    val unanswered = answers.filter { it.state == DnsState.ERROR }.map { it.server }
    return when {
        wrong.isNotEmpty() -> HealthDetail(domain, HealthState.UNHEALTHY, Text(DnsTexts.ANSWERS, "answers" to Joined(wrong.map { it.describe() }, "; ")))
        unanswered.size == answers.size -> HealthDetail(domain, HealthState.UNKNOWN, Text(DnsTexts.NO_ANSWER))
        unanswered.isNotEmpty() -> HealthDetail(domain, HealthState.HEALTHY, Text(DnsTexts.NO_ANSWER_FROM, "servers" to unanswered.joinToString(", ")))
        else -> HealthDetail(domain, HealthState.HEALTHY)
    }
}

// e.g. "8.8.8.8 has A 203.0.113.9, no AAAA"
private fun DnsAnswer.describe(): Text {
    fun records(
        type: String,
        addresses: List<String>,
    ) = if (addresses.isEmpty()) Text(DnsTexts.NO_RECORDS, "type" to type) else Text(DnsTexts.RECORDS, "type" to type, "addresses" to addresses.joinToString(" "))
    return Text(DnsTexts.ANSWER, "server" to server, "a" to records("A", ipv4), "aaaa" to records("AAAA", ipv6))
}

// the texts of the checks of the domains (backend.dns in i18n/texts.yml)
object DnsTexts : Catalog("dns") {
    val NOT_CHECKED = text("not_checked")
    val NO_ADDRESSES = text("no_addresses")
    val NO_DOMAINS = text("no_domains")
    val WRONG_DOMAINS = text("wrong_domains")
    val UNANSWERED_DOMAINS = text("unanswered_domains")
    val RIGHT_DOMAINS = text("right_domains")
    val ANSWERS = text("answers")
    val NO_ANSWER = text("no_answer")
    val NO_ANSWER_FROM = text("no_answer_from")
    val ANSWER = text("answer")
    val RECORDS = text("records")
    val NO_RECORDS = text("no_records")
}
