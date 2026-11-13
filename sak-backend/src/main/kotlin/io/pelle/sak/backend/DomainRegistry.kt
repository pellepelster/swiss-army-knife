package io.pelle.sak.backend

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectRepository

// what buckets and redirects must know of each other: the domains neither may take (reserved or
// redirected) and the redirects that keep a bucket from being deleted. S3BucketService knows the
// domains of the buckets itself
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
