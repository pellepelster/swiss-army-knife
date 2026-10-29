package io.pelle.sak.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// how dns is set up for the deployment (docs/fdr/FDR-001-dns-mode.md): DOMAIN manages
// <root domain> and *.<root domain>, SUBDOMAIN <name>.<root domain> and
// *.<name>.<root domain>; written by the provisioning of the server like the sak name
@Serializable
enum class DnsMode(
    // as the configuration and the api spell it
    val value: String,
) {
    @SerialName("domain")
    DOMAIN("domain"),

    @SerialName("subdomain")
    SUBDOMAIN("subdomain"),
}
