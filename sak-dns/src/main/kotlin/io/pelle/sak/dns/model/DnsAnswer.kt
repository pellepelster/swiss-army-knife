package io.pelle.sak.dns.model

// what one name server answers for a domain
data class DnsAnswer(
    val server: String,
    val state: DnsState,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val error: String? = null,
)
