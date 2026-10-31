package io.pelle.sak.backend

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectRepository

// what buckets and redirects must know of each other, both consult it: the domains a bucket or a
// redirect can not take (the ones the backend serves itself and the redirected ones), so neither
// takes a domain of the other, and the redirects that keep a bucket from being deleted (HOSTING-11).
// The domains of the buckets S3BucketService knows itself
class DomainRegistry(
    // e.g. api.<base_domain> and sak.<base_domain>
    private val reserved: Set<String>,
    private val redirects: RedirectRepository,
) {
    constructor(
        database: SakDatabase,
        reserved: Set<String> = emptySet(),
    ) : this(reserved, RedirectRepository(database))

    fun isReserved(domain: String) = domain in reserved

    fun isRedirected(domain: String) = redirects.byId(domain) != null

    // the source domains of the redirects that lead to the bucket
    fun redirectsTo(bucket: String): List<String> = redirects.sourcesTo(bucket)
}
