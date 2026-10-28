package io.pelle.sak.backend

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectRepository

// the domains a bucket or a redirect can not take: the ones the backend serves itself and the
// redirected ones; buckets and redirects both consult it, so neither takes a domain of the other.
// The domains of the buckets S3BucketService knows itself
class DomainRegistry(
    // e.g. api.<root_domain> and apps.<root_domain>
    private val reserved: Set<String>,
    private val redirects: RedirectRepository,
) {
    constructor(
        database: SakDatabase,
        reserved: Set<String> = emptySet(),
    ) : this(reserved, RedirectRepository(database))

    fun isReserved(domain: String) = domain in reserved

    fun isRedirected(domain: String) = redirects.byId(domain) != null
}
