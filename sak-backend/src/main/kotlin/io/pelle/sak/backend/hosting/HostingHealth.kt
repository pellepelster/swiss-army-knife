package io.pelle.sak.backend.hosting

import io.pelle.sak.config.SakConfig
import io.pelle.sak.dns.DomainDnsCheck
import io.pelle.sak.dns.DomainsHealth

// the buckets are healthy when every domain they are served at, their subdomains of the root
// domain included, points to the server
class HostingHealth(
    buckets: S3BucketService,
    check: DomainDnsCheck,
    addresses: SakConfig.AddressesConfig,
) : DomainsHealth("buckets", { buckets.all().flatMap(buckets::servedDomains) }, check, addresses.ipv4, addresses.ipv6)
