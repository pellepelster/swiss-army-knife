package io.pelle.sak.backend

// what the buckets ask of the redirects. RedirectService answers it: the only way to the redirects
// (LAYER-1)
interface RedirectedDomains {
    fun isRedirected(domain: String): Boolean

    // the source domains of the redirects that lead to the bucket
    fun redirectsTo(bucket: String): List<String>
}

// what buckets and redirects must know of each other: the domains neither may take (reserved or
// redirected) and the redirects that keep a bucket from being deleted. S3BucketService knows the
// domains of the buckets itself. The buckets are made first, the redirects need them: RedirectService
// registers here when it is made, until then nothing is redirected
class DomainRegistry(
    // e.g. api.<base_domain> and sak.<base_domain>
    private val reserved: Set<String> = emptySet(),
) {
    @Volatile
    private var redirects: RedirectedDomains? = null

    fun register(redirects: RedirectedDomains) {
        this.redirects = redirects
    }

    fun isReserved(domain: String) = domain in reserved

    fun isRedirected(domain: String) = redirects?.isRedirected(domain) == true

    // the source domains of the redirects that lead to the bucket
    fun redirectsTo(bucket: String): List<String> = redirects?.redirectsTo(bucket).orEmpty()
}
