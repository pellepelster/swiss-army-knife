package io.pelle.sak.backend.hosting

import io.pelle.sak.s3.storage.S3BucketStorage
import io.pelle.sak.model.s3.BucketKeyPairUpdateResult
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.BackgroundJobs
import io.pelle.sak.s3.storage.isValidBucketName
import io.pelle.sak.backend.isFree
import io.pelle.sak.backend.blobPath
import io.pelle.sak.backend.leftovers
import io.pelle.sak.backend.DomainRegistry
import io.pelle.sak.model.s3.BucketDomainsReplaceResult
import io.pelle.sak.model.s3.BucketInsertResult
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.Secret
import io.pelle.sak.backend.isValidDomain
import io.pelle.sak.model.s3.S3Bucket
import io.pelle.sak.model.s3.S3BucketRepository
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobPath
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

sealed interface BucketCreateResult {
    data class Created(
        val bucket: S3Bucket,
    ) : BucketCreateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : BucketCreateResult

    // a bucket of the name exists, also a deleted one
    data object Exists : BucketCreateResult

    // <name>.<base_domain> is a domain of another bucket, a redirect or the backend
    data object SubdomainTaken : BucketCreateResult

    // another bucket is served at it
    data object DomainTaken : BucketCreateResult
}

sealed interface BucketKeyPairReplaceResult {
    data class Replaced(
        val accessKeyId: String,
        val secretAccessKey: Secret,
    ) : BucketKeyPairReplaceResult

    data object NotFound : BucketKeyPairReplaceResult
}

sealed interface BucketDeleteResult {
    data object Deleted : BucketDeleteResult

    data object NotFound : BucketDeleteResult

    // redirects lead to it, their source domains
    data class Redirected(
        val sources: List<String>,
    ) : BucketDeleteResult
}

sealed interface BucketDomainsUpdateResult {
    data class Updated(
        val bucket: S3Bucket,
    ) : BucketDomainsUpdateResult

    data object NotFound : BucketDomainsUpdateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : BucketDomainsUpdateResult

    data object DomainTaken : BucketDomainsUpdateResult
}

// the only way to the buckets, their key pairs and domains; listeners hear of every change of the
// domains. With a base domain, every bucket is also served at <name>.<base_domain>
class S3BucketService(
    private val repository: S3BucketRepository,
    private val baseDomain: String?,
    // names that are no buckets, e.g. the backend's own sites
    private val reservedNames: Set<String>,
    // domains no bucket can be served at, the ones of the apps and the redirected ones; the redirects
    // register there
    internal val domains: DomainRegistry,
    // the storage of the buckets, none where only their rows are needed
    private val storage: BinaryStorage? = null,
    // the counts of the objects of the buckets, none where only their rows are needed
    private val statistics: BucketStatisticsCache? = null,
) {
    // a bucket is never given a resource id whose directory is in the storage (MODEL-8)
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
        baseDomain: String? = null,
        reservedNames: Set<String> = emptySet(),
        domains: DomainRegistry = DomainRegistry(),
        storage: BinaryStorage? = null,
        statistics: BucketStatisticsCache? = null,
    ) : this(S3BucketRepository(database, clock, free = { storage?.isFree(it) != false }), baseDomain, reservedNames, domains, storage, statistics)

    private val random = SecureRandom()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun all(): List<S3Bucket> = repository.all()

    fun byName(name: String): S3Bucket? = repository.byName(name)

    fun byAccessKeyId(accessKeyId: String): S3Bucket? = repository.byAccessKeyId(accessKeyId)

    // where the bucket is in the storage of the buckets, null for an unknown or deleted one: no
    // request reaches the objects of a deleted bucket
    fun objectsPath(name: String): BlobPath? = repository.resourceId(name)?.blobPath()

    // the directories of the storage that are named as a resource id no bucket has, e.g. left by a
    // restore of the instance (MODEL-8); they are kept, and no new bucket gets one of them
    fun leftovers(): List<String> = storage?.leftovers(repository.resourceIds()).orEmpty()

    // how many objects the bucket has and their bytes (HOSTING-3); null while they are counted, it
    // waits only a moment for them
    suspend fun statistics(name: String): BucketStatistics? = statistics?.get(name)

    // what the buckets do on their own: their objects are counted now, so the first look at them does
    // not wait, and the unfinished uploads go every STALE_UPLOAD_CHECK. objects is where the s3 api
    // keeps them
    fun start(
        jobs: BackgroundJobs,
        objects: S3BucketStorage,
    ) {
        statistics?.warm(all().map { it.name })
        jobs.every("remove the unfinished uploads of the buckets", STALE_UPLOAD_CHECK) {
            removeStaleUploads(objects, this) { statistics?.invalidate(it) }
        }
    }

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

    // <name>.<base_domain>, none without a base domain; every name is a label of a domain (isValidName)
    fun subdomain(name: String): String? = baseDomain?.let { "$name.$it" }

    // the domains of the bucket, then its subdomain of the base domain
    fun servedDomains(bucket: S3Bucket): List<String> = (bucket.domains + listOfNotNull(subdomain(bucket.name))).distinct()

    // the served domains of every bucket, for caddy
    fun domains(): Map<String, List<String>> = all().associate { it.name to servedDomains(it) }

    // a bucket with a new key pair for it alone, served at the domains
    fun create(
        name: String,
        domains: List<String> = emptyList(),
        // who creates it (MODEL-5)
        owner: String,
    ): BucketCreateResult {
        if (!isValidName(name) || name in reservedNames) {
            return BucketCreateResult.Invalid(listOf(ErrorMessage(HostingTexts.INVALID_NAME)))
        }
        if (repository.byName(name) != null) {
            return BucketCreateResult.Exists
        }
        if (subdomain(name)?.let { !available(it) || repository.bucketOfDomain(it) != null } == true) {
            return BucketCreateResult.SubdomainTaken
        }
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return BucketCreateResult.Invalid(INVALID_DOMAIN)
                DomainCheck.Taken -> return BucketCreateResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        // another bucket may have taken the name or a domain since the checks above; an access key id
        // another bucket has (one in 2^100) is drawn again
        repeat(KEY_DRAWS) {
            when (val inserted = repository.insert(name, key(ACCESS_KEY_ALPHABET, 20), key(SECRET_ALPHABET, 40), normalized, owner)) {
                is BucketInsertResult.Inserted -> {
                    // counted at once, the page that shows the new bucket does not wait
                    statistics?.warm(listOf(inserted.bucket.name))
                    if (servedDomains(inserted.bucket).isNotEmpty()) changed()
                    return BucketCreateResult.Created(inserted.bucket)
                }
                BucketInsertResult.NameTaken -> return BucketCreateResult.Exists
                BucketInsertResult.DomainTaken -> return BucketCreateResult.DomainTaken
                BucketInsertResult.AccessKeyTaken -> Unit
            }
        }
        error("no free access key id for the bucket $name")
    }

    // the old key pair stops working at once, so a leaked one ends without the bucket
    fun replaceKeyPair(name: String): BucketKeyPairReplaceResult {
        // an access key id another bucket has (one in 2^100) is drawn again
        repeat(KEY_DRAWS) {
            val accessKeyId = key(ACCESS_KEY_ALPHABET, 20)
            val secret = key(SECRET_ALPHABET, 40)
            when (repository.replaceKeyPair(name, accessKeyId, secret)) {
                BucketKeyPairUpdateResult.Replaced -> return BucketKeyPairReplaceResult.Replaced(accessKeyId, Secret(secret))
                BucketKeyPairUpdateResult.NotFound -> return BucketKeyPairReplaceResult.NotFound
                BucketKeyPairUpdateResult.AccessKeyTaken -> Unit
            }
        }
        error("no free access key id for the bucket $name")
    }

    // serves the bucket at the domains instead of its current ones, the name stays
    fun updateDomains(
        name: String,
        domains: List<String>,
        // who adds the new domains
        owner: String,
    ): BucketDomainsUpdateResult {
        val bucket = repository.byName(name) ?: return BucketDomainsUpdateResult.NotFound
        val normalized =
            when (val checked = checkDomains(name, domains)) {
                DomainCheck.Invalid -> return BucketDomainsUpdateResult.Invalid(INVALID_DOMAIN)
                DomainCheck.Taken -> return BucketDomainsUpdateResult.DomainTaken
                is DomainCheck.Valid -> checked.domains
            }
        when (repository.replaceDomains(name, normalized, owner)) {
            BucketDomainsReplaceResult.Replaced -> Unit
            // deleted since the read above
            BucketDomainsReplaceResult.NotFound -> return BucketDomainsUpdateResult.NotFound
            BucketDomainsReplaceResult.DomainTaken -> return BucketDomainsUpdateResult.DomainTaken
        }
        changed()
        return BucketDomainsUpdateResult.Updated(repository.byName(name) ?: bucket.copy(domains = normalized))
    }

    // not while a redirect leads to it. The bucket stays as deleted, served nowhere, its key pair
    // refused; its name stays taken and its objects stay in the storage until it is purged
    fun delete(name: String): BucketDeleteResult {
        domains.redirectsTo(name).takeIf { it.isNotEmpty() }?.let { return BucketDeleteResult.Redirected(it) }
        if (!repository.delete(name)) return BucketDeleteResult.NotFound
        changed()
        return BucketDeleteResult.Deleted
    }

    private sealed interface DomainCheck {
        data class Valid(
            val domains: List<String>,
        ) : DomainCheck

        data object Invalid : DomainCheck

        data object Taken : DomainCheck
    }

    // lower case, without duplicates, none reserved or served for another bucket (also as its
    // subdomain of the base domain)
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

        // no valid domain, or one the backend serves itself or redirects
        private val INVALID_DOMAIN = listOf(ErrorMessage(HostingTexts.INVALID_DOMAIN))

        // how often a key pair is drawn until its access key id is free
        private const val KEY_DRAWS = 3

        // the rule of sak-s3, which stores the bucket
        fun isValidName(name: String) = isValidBucketName(name)
    }
}
