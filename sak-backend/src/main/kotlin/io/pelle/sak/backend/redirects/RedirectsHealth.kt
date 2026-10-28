package io.pelle.sak.backend.redirects

import io.pelle.sak.config.SakConfig
import io.pelle.sak.dns.DomainDnsCheck
import io.pelle.sak.dns.DomainsHealth

// the redirects are healthy when every redirected domain points to the server, caddy answers
// it with the redirect
class RedirectsHealth(
    redirects: RedirectService,
    check: DomainDnsCheck,
    addresses: SakConfig.AddressesConfig,
) : DomainsHealth("Redirect domains", "redirects", { redirects.all().map { it.sourceDomain } }, check, addresses.ipv4, addresses.ipv6)
