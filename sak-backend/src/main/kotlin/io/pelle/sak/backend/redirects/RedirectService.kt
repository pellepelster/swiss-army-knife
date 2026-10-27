package io.pelle.sak.backend.redirects

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.isValidDomain
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.model.ingress.Redirect
import io.pelle.sak.model.ingress.RedirectRepository
import io.pelle.sak.model.ingress.RedirectTarget
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

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

sealed interface UpdateRedirectResult {
    data class Updated(
        val redirect: Redirect,
    ) : UpdateRedirectResult

    data object NotFound : UpdateRedirectResult

    // no valid domain or the source itself
    data object InvalidDomain : UpdateRedirectResult

    data object UnknownBucket : UpdateRedirectResult

    // the bucket is not served at a domain, there is nothing to redirect to
    data object BucketNotServed : UpdateRedirectResult
}

sealed interface DeleteRedirectResult {
    data object Deleted : DeleteRedirectResult

    data object NotFound : DeleteRedirectResult
}

// the domains caddy redirects, the only way to them; listeners hear of every change
class RedirectService(
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

    fun byId(sourceDomain: String): Redirect? = repository.byId(sourceDomain.lowercase())

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
            when (val checked = check(source, target)) {
                is CheckedTarget.Valid -> checked.target
                CheckedTarget.InvalidDomain -> return CreateRedirectResult.InvalidDomain
                CheckedTarget.UnknownBucket -> return CreateRedirectResult.UnknownBucket
                CheckedTarget.BucketNotServed -> return CreateRedirectResult.BucketNotServed
            }

        val redirect = Redirect(source, normalized, clock.instant())
        if (!repository.insert(redirect)) {
            return CreateRedirectResult.Exists
        }
        changed()
        return CreateRedirectResult.Created(redirect)
    }

    // the target the redirect of the source leads to, checked as when it is created
    fun update(
        sourceDomain: String,
        target: RedirectTarget,
    ): UpdateRedirectResult {
        val source = sourceDomain.lowercase()
        val redirect = repository.byId(source) ?: return UpdateRedirectResult.NotFound
        val normalized =
            when (val checked = check(source, target)) {
                is CheckedTarget.Valid -> checked.target
                CheckedTarget.InvalidDomain -> return UpdateRedirectResult.InvalidDomain
                CheckedTarget.UnknownBucket -> return UpdateRedirectResult.UnknownBucket
                CheckedTarget.BucketNotServed -> return UpdateRedirectResult.BucketNotServed
            }
        val now = clock.instant()
        if (!repository.update(source, normalized, now)) return UpdateRedirectResult.NotFound
        changed()
        return UpdateRedirectResult.Updated(redirect.copy(target = normalized, updatedAt = now))
    }

    fun delete(sourceDomain: String): DeleteRedirectResult {
        if (!repository.delete(sourceDomain.lowercase())) {
            return DeleteRedirectResult.NotFound
        }
        changed()
        return DeleteRedirectResult.Deleted
    }

    private sealed interface CheckedTarget {
        data class Valid(
            val target: RedirectTarget,
        ) : CheckedTarget

        data object InvalidDomain : CheckedTarget

        data object UnknownBucket : CheckedTarget

        data object BucketNotServed : CheckedTarget
    }

    // the target with its domain in lower case, a domain must be valid and differ from the source,
    // a bucket must be served at a domain
    private fun check(
        source: String,
        target: RedirectTarget,
    ): CheckedTarget =
        when (target) {
            is RedirectTarget.Domain -> {
                val domain = target.domain.lowercase()
                if (!isValidDomain(domain) || domain == source) CheckedTarget.InvalidDomain else CheckedTarget.Valid(RedirectTarget.Domain(domain))
            }
            is RedirectTarget.Bucket -> {
                val bucket = buckets.byName(target.bucket)
                when {
                    bucket == null -> CheckedTarget.UnknownBucket
                    buckets.servedDomains(bucket).isEmpty() -> CheckedTarget.BucketNotServed
                    else -> CheckedTarget.Valid(target)
                }
            }
        }

    private fun changed() = listeners.forEach { it() }
}
