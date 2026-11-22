package io.pelle.sak.backend.info.model

import io.pelle.sak.config.DnsMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// the dns mode of the deployment as it is answered, as the configuration spells it
@Serializable
enum class DnsModeResponse(
    val dnsMode: DnsMode,
) {
    @SerialName("domain")
    DOMAIN(DnsMode.DOMAIN),

    @SerialName("subdomain")
    SUBDOMAIN(DnsMode.SUBDOMAIN),
    ;

    companion object {
        fun of(dnsMode: DnsMode) = entries.first { it.dnsMode == dnsMode }
    }
}
