package io.pelle.sak.model.s3

import io.pelle.sak.model.SakDatabase
import java.security.SecureRandom
import java.time.Clock

sealed interface CreateBucketResult {
    data class Created(
        val bucket: S3Bucket,
    ) : CreateBucketResult

    data object InvalidName : CreateBucketResult

    data object NameTaken : CreateBucketResult
}

// the buckets of the s3 api and the key pair of each, the only way to them
class S3BucketService internal constructor(
    private val repository: S3BucketRepository,
    private val clock: Clock,
    // names that are no buckets, e.g. the controller's own sites
    private val reservedNames: Set<String>,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
        reservedNames: Set<String> = emptySet(),
    ) : this(S3BucketRepository(database), clock, reservedNames)

    private val random = SecureRandom()

    fun all(): List<S3Bucket> = repository.all()

    fun byName(name: String): S3Bucket? = repository.byName(name)

    fun byAccessKeyId(accessKeyId: String): S3Bucket? = repository.byAccessKeyId(accessKeyId)

    // a bucket with a new key pair for it alone
    fun create(name: String): CreateBucketResult {
        if (!isValidName(name) || name in reservedNames) {
            return CreateBucketResult.InvalidName
        }
        val bucket = S3Bucket(name, key(ACCESS_KEY_ALPHABET, 20), key(SECRET_ALPHABET, 40), clock.instant())
        return if (repository.insert(bucket)) CreateBucketResult.Created(bucket) else CreateBucketResult.NameTaken
    }

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
