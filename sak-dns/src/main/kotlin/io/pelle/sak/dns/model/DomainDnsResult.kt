package io.pelle.sak.dns.model

// what every name server answers for a domain
data class DomainDnsResult(
    val domain: String,
    val answers: List<DnsAnswer>,
)
