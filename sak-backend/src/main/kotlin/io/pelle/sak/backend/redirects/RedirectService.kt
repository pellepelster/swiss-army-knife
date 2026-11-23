package io.pelle.sak.backend.redirects

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.backend.RedirectedDomains
import io.pelle.sak.backend.DomainRegistry
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.isValidDomain
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.model.redirects.Redirect
import io.pelle.sak.model.redirects.RedirectRepository
import io.pelle.sak.model.redirects.RedirectTarget
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

sealed interface RedirectCreateResult {
    data class Created(
        val redirect: Redirect,
    ) : RedirectCreateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : RedirectCreateResult

    data object UnknownBucket : RedirectCreateResult

    // the bucket is not served at a domain, there is nothing to redirect to
    data object BucketNotServed : RedirectCreateResult

    data object Exists : RedirectCreateResult
}

sealed interface RedirectUpdateResult {
    data class Updated(
        val redirect: Redirect,
    ) : RedirectUpdateResult

    data object NotFound : RedirectUpdateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : RedirectUpdateResult

    data object UnknownBucket : RedirectUpdateResult

    // the bucket is not served at a domain, there is nothing to redirect to
    data object BucketNotServed : RedirectUpdateResult
}

// the domains caddy redirects, the only way to them; listeners hear of every change
class RedirectService(
    private val repository: RedirectRepository,
    private val buckets: S3BucketService,
    // the domains the backend serves itself, which can not be redirected; the one of the buckets, so
    // they learn of the redirects
    private val domains: DomainRegistry,
) : RedirectedDomains {
    constructor(
        database: SakDatabase,
        buckets: S3BucketService,
        clock: Clock = Clock.systemUTC(),
        domains: DomainRegistry = buckets.domains,
    ) : this(RedirectRepository(database, clock), buckets, domains)

    init {
        domains.register(this)
    }

    override fun isRedirected(domain: String): Boolean = repository.bySourceDomain(domain) != null

    override fun redirectsTo(bucket: String): List<String> = repository.sourcesTo(bucket)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<Redirect> = repository.all()

    fun bySourceDomain(sourceDomain: String): Redirect? = repository.bySourceDomain(sourceDomain.lowercase())

    // the domain the redirect leads to, null for a bucket no longer served at a domain
    fun location(target: RedirectTarget): String? =
        when (target) {
            is RedirectTarget.Domain -> target.domain
            is RedirectTarget.Bucket -> buckets.byName(target.bucket)?.let { buckets.servedDomains(it).firstOrNull() }
        }

    // for caddy; a redirect to a bucket no longer served at a domain is left out
    fun locations(): Map<String, String> =
        all()
            .mapNotNull { redirect ->
                val location = location(redirect.target)
                if (location == null) logger.warn { "the redirect of '${redirect.sourceDomain}' leads nowhere, it is left out" }
                location?.let { redirect.sourceDomain to it }
            }.toMap()

    fun create(
        sourceDomain: String,
        target: RedirectTarget,
        // who creates it (MODEL-5)
        owner: String,
    ): RedirectCreateResult {
        val source = sourceDomain.lowercase()
        if (!isValidDomain(source) || domains.isReserved(source) || buckets.bucketOfDomain(source) != null) {
            return RedirectCreateResult.Invalid(INVALID_DOMAINS)
        }
        val normalized =
            when (val checked = check(source, target)) {
                is CheckedTarget.Valid -> checked.target
                CheckedTarget.InvalidDomain -> return RedirectCreateResult.Invalid(INVALID_DOMAINS)
                CheckedTarget.UnknownBucket -> return RedirectCreateResult.UnknownBucket
                CheckedTarget.BucketNotServed -> return RedirectCreateResult.BucketNotServed
            }

        val redirect = repository.insert(source, normalized, owner) ?: return RedirectCreateResult.Exists
        changed()
        return RedirectCreateResult.Created(redirect)
    }

    // the target is checked as when it is created
    fun update(
        sourceDomain: String,
        target: RedirectTarget,
    ): RedirectUpdateResult {
        val source = sourceDomain.lowercase()
        repository.bySourceDomain(source) ?: return RedirectUpdateResult.NotFound
        val normalized =
            when (val checked = check(source, target)) {
                is CheckedTarget.Valid -> checked.target
                CheckedTarget.InvalidDomain -> return RedirectUpdateResult.Invalid(INVALID_TARGET)
                CheckedTarget.UnknownBucket -> return RedirectUpdateResult.UnknownBucket
                CheckedTarget.BucketNotServed -> return RedirectUpdateResult.BucketNotServed
            }
        val updated = repository.update(source, normalized) ?: return RedirectUpdateResult.NotFound
        changed()
        return RedirectUpdateResult.Updated(updated)
    }

    fun delete(sourceDomain: String): DeleteResult {
        if (repository.delete(sourceDomain.lowercase()) == null) {
            return DeleteResult.NotFound
        }
        changed()
        return DeleteResult.Deleted
    }

    private companion object {
        // no valid domain, the source is the target, or a domain the backend or a bucket serves
        val INVALID_DOMAINS = listOf(ErrorMessage(RedirectsTexts.INVALID_DOMAINS))

        // no valid domain or the source itself
        val INVALID_TARGET = listOf(ErrorMessage(RedirectsTexts.INVALID_TARGET))
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
