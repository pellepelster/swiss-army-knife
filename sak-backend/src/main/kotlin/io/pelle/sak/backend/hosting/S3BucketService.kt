package io.pelle.sak.backend.hosting

import io.pelle.sak.backend.DomainRegistry
import io.pelle.sak.model.s3.InsertBucketResult
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.Secret
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

    // <name>.<base_domain> is a domain of another bucket, a redirect or the controller
    data object SubdomainTaken : CreateBucketResult

    // no valid domain, or one the controller serves itself or redirects
    data object InvalidDomain : CreateBucketResult

    // another bucket is served at it
    data object DomainTaken : CreateBucketResult
}

sealed interface DeleteBucketResult {
    data object Deleted : DeleteBucketResult

    data object NotFound : DeleteBucketResult
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
// <name>.<base_domain> when the root domain is given
class S3BucketService(
    private val repository: S3BucketRepository,
    // the domain the buckets get their subdomain of, the base domain of the deployment
    private val baseDomain: String?,
    // names that are no buckets, e.g. the controller's own sites
    private val reservedNames: Set<String>,
    // domains no bucket can be served at, the ones of the apps and the redirected ones
    private val domains: DomainRegistry,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
        baseDomain: String? = null,
        reservedNames: Set<String> = emptySet(),
        domains: DomainRegistry = DomainRegistry(database),
    ) : this(S3BucketRepository(database, clock), baseDomain, reservedNames, domains)

    private val random = SecureRandom()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<S3Bucket> = repository.all()

    fun byName(name: String): S3Bucket? = repository.byName(name)

    fun byAccessKeyId(accessKeyId: String): S3Bucket? = repository.byAccessKeyId(accessKeyId)

    // the secret of the key pair, for sigv4 to verify the requests signed with it
    fun secretAccessKey(accessKeyId: String): Secret? =
        repository.secretAccessKey(accessKeyId)?.let(::Secret)

    // the key pair of the bucket, null for an unknown one
    fun keyPair(name: String): Pair<String, Secret>? = repository.keyPair(name)?.let { (accessKeyId, secret) -> accessKeyId to Secret(secret) }

    // the bucket served at the domain, one of its own or its subdomain of the base domain
    fun bucketOfDomain(domain: String): String? {
        val normalized = domain.lowercase()
        return repository.bucketOfDomain(normalized)
            ?: baseDomain
                ?.let { normalized.removeSuffix(".$it").takeIf { name -> name != normalized } }
                ?.takeIf { isValidName(it) && repository.byName(it) != null }
    }

    // <name>.<base_domain>, none without a base domain or for a name from before only letters,
    // digits and hyphens were allowed
    fun subdomain(name: String): String? = baseDomain?.let { "$name.$it" }?.takeIf { isValidName(name) }

    // the domains of the bucket, then its subdomain of the base domain
    fun servedDomains(bucket: S3Bucket): List<String> = (bucket.domains + listOfNotNull(subdomain(bucket.name))).distinct()

    // the domains every bucket is served at, what caddy serves them at
    fun domains(): Map<String, List<String>> = all().associate { it.name to servedDomains(it) }

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
        if (subdomain(name)?.let { !available(it) || repository.bucketOfDomain(it) != null } == true) {
            return CreateBucketResult.SubdomainTaken
        }
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return CreateBucketResult.InvalidDomain
                DomainCheck.Taken -> return CreateBucketResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        // another bucket may have taken the name or a domain since the checks above
        val bucket =
            when (val inserted = repository.insert(name, key(ACCESS_KEY_ALPHABET, 20), key(SECRET_ALPHABET, 40), normalized)) {
                is InsertBucketResult.Inserted -> inserted.bucket
                InsertBucketResult.NameTaken -> return CreateBucketResult.NameTaken
                InsertBucketResult.DomainTaken -> return CreateBucketResult.DomainTaken
            }
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
        return UpdateDomainsResult.Updated(repository.byName(name) ?: bucket.copy(domains = normalized))
    }

    // the bucket stays as deleted and is served nowhere any more, its key pair is refused; its name
    // stays taken and its objects stay in the storage until it is purged
    fun delete(name: String): DeleteBucketResult {
        if (!repository.delete(name)) return DeleteBucketResult.NotFound
        changed()
        return DeleteBucketResult.Deleted
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
        if (normalized.any { !isValidDomain(it) || !available(it) }) {
            return DomainCheck.Invalid
        }
        if (normalized.any { domain -> bucketOfDomain(domain).let { it != null && it != name } }) {
            return DomainCheck.Taken
        }
        return DomainCheck.Valid(normalized)
    }

    // neither one of the apps nor redirected
    private fun available(domain: String) = !domains.isReserved(domain) && !domains.isRedirected(domain)

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
