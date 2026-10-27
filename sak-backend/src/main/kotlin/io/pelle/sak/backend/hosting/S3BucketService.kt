package io.pelle.sak.backend.hosting

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.isValidDomain
import io.pelle.sak.model.s3.S3Bucket
import io.pelle.sak.model.s3.S3BucketRepository
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

sealed interface CreateBucketResult {
    data class Created(
        val bucket: S3Bucket,
    ) : CreateBucketResult

    data object InvalidName : CreateBucketResult

    data object NameTaken : CreateBucketResult

    // <name>.<root_domain> is a domain of another bucket, a redirect or the controller
    data object SubdomainTaken : CreateBucketResult

    // no valid domain, or one the controller serves itself or redirects
    data object InvalidDomain : CreateBucketResult

    // another bucket is served at it
    data object DomainTaken : CreateBucketResult
}

sealed interface UpdateDomainsResult {
    data class Updated(
        val bucket: S3Bucket,
    ) : UpdateDomainsResult

    data object NotFound : UpdateDomainsResult

    data object InvalidDomain : UpdateDomainsResult

    data object DomainTaken : UpdateDomainsResult
}

// the buckets of the s3 api, the key pair of each and the domains they are served at, the only
// way to them; listeners hear of every change of the domains. Every bucket is also served at
// <name>.<root_domain> when the root domain is given
class S3BucketService(
    private val repository: S3BucketRepository,
    private val rootDomain: String?,
    // names that are no buckets, e.g. the controller's own sites
    private val reservedNames: Set<String>,
    // domains no bucket can be served at, e.g. the ones of the apps or redirected ones
    private val reservedDomains: () -> Set<String>,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
        rootDomain: String? = null,
        reservedNames: Set<String> = emptySet(),
        reservedDomains: () -> Set<String> = { emptySet() },
    ) : this(S3BucketRepository(database, clock), rootDomain, reservedNames, reservedDomains)

    private val random = SecureRandom()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<S3Bucket> = repository.all()

    fun byName(name: String): S3Bucket? = repository.byName(name)

    fun byAccessKeyId(accessKeyId: String): S3Bucket? = repository.byAccessKeyId(accessKeyId)

    // the bucket served at the domain, one of its own or its subdomain of the root domain
    fun bucketOfDomain(domain: String): String? {
        val normalized = domain.lowercase()
        return repository.bucketOfDomain(normalized)
            ?: rootDomain
                ?.let { normalized.removeSuffix(".$it").takeIf { name -> name != normalized } }
                ?.takeIf { isValidName(it) && repository.byName(it) != null }
    }

    // <name>.<root_domain>, none without a root domain or for a name from before only letters,
    // digits and hyphens were allowed
    fun subdomain(name: String): String? = rootDomain?.let { "$name.$it" }?.takeIf { isValidName(name) }

    // the domains of the bucket, then its subdomain of the root domain
    fun servedDomains(bucket: S3Bucket): List<String> = (bucket.domains + listOfNotNull(subdomain(bucket.name))).distinct()

    // a bucket with a new key pair for it alone, served at the domains
    fun create(
        name: String,
        domains: List<String> = emptyList(),
    ): CreateBucketResult {
        if (!isValidName(name) || name in reservedNames) {
            return CreateBucketResult.InvalidName
        }
        if (repository.byName(name) != null) {
            return CreateBucketResult.NameTaken
        }
        if (subdomain(name)?.let { it in reservedDomains() || repository.bucketOfDomain(it) != null } == true) {
            return CreateBucketResult.SubdomainTaken
        }
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return CreateBucketResult.InvalidDomain
                DomainCheck.Taken -> return CreateBucketResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        val bucket =
            repository.insert(name, key(ACCESS_KEY_ALPHABET, 20), key(SECRET_ALPHABET, 40), normalized)
                ?: return CreateBucketResult.NameTaken
        if (servedDomains(bucket).isNotEmpty()) changed()
        return CreateBucketResult.Created(bucket)
    }

    // serves the bucket at the domains instead of its current ones, the name stays
    fun updateDomains(
        name: String,
        domains: List<String>,
    ): UpdateDomainsResult {
        val bucket = repository.byName(name) ?: return UpdateDomainsResult.NotFound
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return UpdateDomainsResult.InvalidDomain
                DomainCheck.Taken -> return UpdateDomainsResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        if (!repository.replaceDomains(name, normalized)) {
            return UpdateDomainsResult.DomainTaken
        }
        changed()
        return UpdateDomainsResult.Updated(bucket.copy(domains = normalized))
    }

    private sealed interface DomainCheck {
        data class Valid(
            val domains: List<String>,
        ) : DomainCheck

        data object Invalid : DomainCheck

        data object Taken : DomainCheck
    }

    // lower case, without duplicates, none reserved or served for another bucket, its subdomain
    // of the root domain included
    private fun checkDomains(
        name: String,
        domains: List<String>,
    ): DomainCheck {
        val normalized = domains.map { it.trim().lowercase() }.distinct()
        val reserved = reservedDomains()
        if (normalized.any { !isValidDomain(it) || it in reserved }) {
            return DomainCheck.Invalid
        }
        if (normalized.any { domain -> bucketOfDomain(domain).let { it != null && it != name } }) {
            return DomainCheck.Taken
        }
        return DomainCheck.Valid(normalized)
    }

    private fun changed() = listeners.forEach { it() }

    private fun key(
        alphabet: String,
        length: Int,
    ) = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    companion object {
        private const val ACCESS_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private const val SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        // 3 to 63 lower case letters, digits and hyphens, starting and ending with a letter or
        // digit: a valid s3 bucket name and a valid label of a domain, the bucket's subdomain
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$")

        fun isValidName(name: String) = NAME.matches(name)
    }
}
