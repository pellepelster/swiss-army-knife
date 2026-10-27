package io.pelle.sak.model.s3

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.isValidDomain
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

sealed interface CreateBucketResult {
    data class Created(
        val bucket: S3Bucket,
    ) : CreateBucketResult

    data object InvalidName : CreateBucketResult

    data object NameTaken : CreateBucketResult

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
// way to them; listeners hear of every change of the domains
class S3BucketService internal constructor(
    private val repository: S3BucketRepository,
    private val clock: Clock,
    // names that are no buckets, e.g. the controller's own sites
    private val reservedNames: Set<String>,
    // domains no bucket can be served at, e.g. the console's or redirected ones
    private val reservedDomains: () -> Set<String>,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
        reservedNames: Set<String> = emptySet(),
        reservedDomains: () -> Set<String> = { emptySet() },
    ) : this(S3BucketRepository(database), clock, reservedNames, reservedDomains)

    private val random = SecureRandom()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<S3Bucket> = repository.all()

    fun byName(name: String): S3Bucket? = repository.byName(name)

    fun byAccessKeyId(accessKeyId: String): S3Bucket? = repository.byAccessKeyId(accessKeyId)

    fun bucketOfDomain(domain: String): String? = repository.bucketOfDomain(domain.lowercase())

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
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return CreateBucketResult.InvalidDomain
                DomainCheck.Taken -> return CreateBucketResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        val bucket = S3Bucket(name, key(ACCESS_KEY_ALPHABET, 20), key(SECRET_ALPHABET, 40), clock.instant(), normalized)
        if (!repository.insert(bucket)) {
            return CreateBucketResult.NameTaken
        }
        if (normalized.isNotEmpty()) changed()
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

    // lower case, without duplicates, none reserved or served for another bucket
    private fun checkDomains(
        name: String,
        domains: List<String>,
    ): DomainCheck {
        val normalized = domains.map { it.trim().lowercase() }.distinct()
        val reserved = reservedDomains()
        if (normalized.any { !isValidDomain(it) || it in reserved }) {
            return DomainCheck.Invalid
        }
        if (normalized.any { domain -> repository.bucketOfDomain(domain).let { it != null && it != name } }) {
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

        // the s3 bucket naming rules: 3 to 63 lower case letters, digits, dots and hyphens,
        // starting and ending with a letter or digit, no ip address
        private val NAME = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")

        private val IP_ADDRESS = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

        fun isValidName(name: String) = NAME.matches(name) && !name.contains("..") && !IP_ADDRESS.matches(name)
    }
}
