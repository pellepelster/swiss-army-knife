package io.pelle.sak.model.ingress

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.isValidDomain
import io.pelle.sak.model.s3.S3BucketService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// where a redirected domain leads to
sealed interface RedirectTarget {
    data class Domain(
        val domain: String,
    ) : RedirectTarget

    // the first domain the bucket is served at
    data class Bucket(
        val bucket: String,
    ) : RedirectTarget
}

data class Redirect(
    val sourceDomain: String,
    val target: RedirectTarget,
    val createdAt: Instant,
)

sealed interface CreateRedirectResult {
    data class Created(
        val redirect: Redirect,
    ) : CreateRedirectResult

    // no valid domain, the source is the target, or a domain the controller or a bucket serves
    data object InvalidDomain : CreateRedirectResult

    data object UnknownBucket : CreateRedirectResult

    // the bucket is not served at a domain, there is nothing to redirect to
    data object BucketNotServed : CreateRedirectResult

    data object Exists : CreateRedirectResult
}

sealed interface DeleteRedirectResult {
    data object Deleted : DeleteRedirectResult

    data object NotFound : DeleteRedirectResult
}

// the domains caddy redirects, the only way to them; listeners hear of every change
class RedirectService internal constructor(
    private val repository: RedirectRepository,
    private val buckets: S3BucketService,
    private val clock: Clock,
    // the domains the controller serves itself, which can not be redirected
    private val reservedDomains: () -> Set<String>,
) {
    constructor(
        database: SakDatabase,
        buckets: S3BucketService,
        clock: Clock = Clock.systemUTC(),
        reservedDomains: () -> Set<String> = { emptySet() },
    ) : this(RedirectRepository(database), buckets, clock, reservedDomains)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<Redirect> = repository.all()

    // the redirected domains, no bucket may be served at them
    fun sourceDomains(): Set<String> = repository.all().map { it.sourceDomain }.toSet()

    // the domain the redirect leads to, null for a bucket no longer served at a domain
    fun location(target: RedirectTarget): String? =
        when (target) {
            is RedirectTarget.Domain -> target.domain
            is RedirectTarget.Bucket -> buckets.byName(target.bucket)?.let { buckets.servedDomains(it).firstOrNull() }
        }

    fun create(
        sourceDomain: String,
        target: RedirectTarget,
    ): CreateRedirectResult {
        val source = sourceDomain.lowercase()
        if (!isValidDomain(source) || source in reservedDomains() || buckets.bucketOfDomain(source) != null) {
            return CreateRedirectResult.InvalidDomain
        }
        val normalized =
            when (target) {
                is RedirectTarget.Domain -> {
                    val domain = target.domain.lowercase()
                    if (!isValidDomain(domain) || domain == source) return CreateRedirectResult.InvalidDomain
                    RedirectTarget.Domain(domain)
                }
                is RedirectTarget.Bucket -> {
                    val bucket = buckets.byName(target.bucket) ?: return CreateRedirectResult.UnknownBucket
                    if (buckets.servedDomains(bucket).isEmpty()) return CreateRedirectResult.BucketNotServed
                    target
                }
            }

        val redirect = Redirect(source, normalized, clock.instant())
        if (!repository.insert(redirect)) {
            return CreateRedirectResult.Exists
        }
        changed()
        return CreateRedirectResult.Created(redirect)
    }

    fun delete(sourceDomain: String): DeleteRedirectResult {
        if (!repository.delete(sourceDomain.lowercase())) {
            return DeleteRedirectResult.NotFound
        }
        changed()
        return DeleteRedirectResult.Deleted
    }

    private fun changed() = listeners.forEach { it() }
}
