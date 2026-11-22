package io.pelle.sak.model.s3

import io.pelle.sak.model.ResourceIds
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.S3BucketsRecord
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS_DOMAINS
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.security.SecureRandom
import java.time.Instant
import java.time.Clock

// what inserting a bucket gives: the bucket, or which of its values another bucket has
sealed interface BucketInsertResult {
    data class Inserted(
        val bucket: S3Bucket,
    ) : BucketInsertResult

    // the name, also the one of a deleted bucket
    data object NameTaken : BucketInsertResult

    // one of the domains
    data object DomainTaken : BucketInsertResult

    // the access key id, also the one of a deleted bucket: the caller draws another one
    data object AccessKeyTaken : BucketInsertResult
}

// what replacing the domains of a bucket gives
sealed interface BucketDomainsReplaceResult {
    data object Replaced : BucketDomainsReplaceResult

    // there is no live bucket of the name
    data object NotFound : BucketDomainsReplaceResult

    // one of the domains belongs to another bucket
    data object DomainTaken : BucketDomainsReplaceResult
}

// the buckets and their domains, for S3BucketService only
class S3BucketRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
    // whether a resource id may be given out beside the database, e.g. no directory of it is left from
    // a restore of the instance (MODEL-8)
    free: (ResourceId) -> Boolean = { true },
    random: SecureRandom = SecureRandom(),
) {
    private val resourceIds = ResourceIds(S3_BUCKETS, S3_BUCKETS.RESOURCE_ID, free, random)

    fun all(): List<S3Bucket> {
        val domains = domainsByBucket(database.dsl, null)
        return database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.DELETED_AT.isNull)
            .orderBy(S3_BUCKETS.NAME)
            .fetch()
            .map { it.toBucket(domains[it.name].orEmpty()) }
    }

    fun byName(name: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { it.toBucket(domainsByBucket(database.dsl, name)[name].orEmpty()) }

    fun byAccessKeyId(accessKeyId: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.ACCESS_KEY_ID.eq(accessKeyId), S3_BUCKETS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { it.toBucket(domainsByBucket(database.dsl, it.name)[it.name].orEmpty()) }

    // the secret of a live bucket's key pair, never part of S3Bucket so it can't reach the api
    fun secretAccessKey(accessKeyId: String): String? =
        database.dsl
            .select(S3_BUCKETS.SECRET_ACCESS_KEY)
            .from(S3_BUCKETS)
            .where(S3_BUCKETS.ACCESS_KEY_ID.eq(accessKeyId), S3_BUCKETS.DELETED_AT.isNull)
            .fetchOne(S3_BUCKETS.SECRET_ACCESS_KEY)

    // the access key id and the secret of a live bucket's key pair
    fun keyPair(name: String): Pair<String, String>? =
        database.dsl
            .select(S3_BUCKETS.ACCESS_KEY_ID, S3_BUCKETS.SECRET_ACCESS_KEY)
            .from(S3_BUCKETS)
            .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { it.value1()!! to it.value2()!! }

    // the bucket served at the domain
    fun bucketOfDomain(domain: String): String? =
        database.dsl
            .select(S3_BUCKETS_DOMAINS.BUCKET_NAME)
            .from(S3_BUCKETS_DOMAINS)
            .where(S3_BUCKETS_DOMAINS.DOMAIN.eq(domain), S3_BUCKETS_DOMAINS.DELETED_AT.isNull)
            .fetchOne(S3_BUCKETS_DOMAINS.BUCKET_NAME)

    // the resource id of a live bucket, null when there is none: where its objects are (MODEL-8)
    fun resourceId(name: String): ResourceId? =
        database.dsl
            .select(S3_BUCKETS.RESOURCE_ID)
            .from(S3_BUCKETS)
            .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
            .fetchOne(S3_BUCKETS.RESOURCE_ID)
            ?.let(::ResourceId)

    // the resource ids of all buckets, the deleted ones included: their directories are no leftovers. The
    // backend looks for leftovers on its own, so there is no user to check (MODEL-6)
    fun resourceIds(): Set<ResourceId> = resourceIds.all(database.dsl)

    // the unique constraints decide, so a bucket created at the same time is told apart: by the
    // name taken (deleted buckets included), the access key id taken (deleted buckets included), else
    // a domain
    fun insert(
        name: String,
        accessKeyId: String,
        secretAccessKey: String,
        domains: List<String> = emptyList(),
        // who creates it, also of its domains (MODEL-5)
        owner: String,
    ): BucketInsertResult =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                val bucket = S3Bucket(name, resourceIds.new(dsl), accessKeyId, clock.instant(), null, domains, owner)
                dsl
                    .insertInto(S3_BUCKETS)
                    .set(S3_BUCKETS.NAME, bucket.name)
                    .set(S3_BUCKETS.RESOURCE_ID, bucket.resourceId.value)
                    .set(S3_BUCKETS.ACCESS_KEY_ID, bucket.accessKeyId)
                    .set(S3_BUCKETS.SECRET_ACCESS_KEY, secretAccessKey)
                    .set(S3_BUCKETS.CREATED_AT, bucket.createdAt)
                    .set(S3_BUCKETS.OWNER, owner)
                    .execute()
                bucket.domains.forEachIndexed { position, domain -> insertDomain(dsl, bucket.name, domain, position, bucket.createdAt, owner) }
                BucketInsertResult.Inserted(bucket)
            }
        } catch (_: IntegrityConstraintViolationException) {
            when {
                database.dsl.fetchExists(S3_BUCKETS, S3_BUCKETS.NAME.eq(name)) -> BucketInsertResult.NameTaken
                database.dsl.fetchExists(S3_BUCKETS, S3_BUCKETS.ACCESS_KEY_ID.eq(accessKeyId)) -> BucketInsertResult.AccessKeyTaken
                else -> BucketInsertResult.DomainTaken
            }
        }

    // the domains that stay keep their row and take their new position, the ones removed are
    // deleted, the ones added get a row of their own. Only of a live bucket, checked in the
    // transaction: a bucket deleted meanwhile gets no domain (MODEL-3)
    fun replaceDomains(
        name: String,
        domains: List<String>,
        // who adds the domains that are new
        owner: String,
    ): BucketDomainsReplaceResult =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                val now = clock.instant()
                if (!dsl.fetchExists(S3_BUCKETS, S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)) {
                    return@transactionResult BucketDomainsReplaceResult.NotFound
                }
                val rows =
                    dsl
                        .selectFrom(S3_BUCKETS_DOMAINS)
                        .where(S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name), S3_BUCKETS_DOMAINS.DELETED_AT.isNull)
                        .fetch()
                        .associateBy { it.domain!! }
                // the same domains in the same order are no change of the bucket
                val unchanged = rows.values.sortedBy { it.position }.map { it.domain } == domains
                val removed = rows.filterKeys { it !in domains }.values.map { it.id }
                dsl
                    .update(S3_BUCKETS_DOMAINS)
                    .set(S3_BUCKETS_DOMAINS.DELETED_AT, now)
                    .where(S3_BUCKETS_DOMAINS.ID.`in`(removed))
                    .execute()
                // moved in two steps, a position is taken once among the domains of the bucket
                val moved = domains.withIndex().filter { (position, domain) -> rows[domain]?.let { it.position != position } == true }
                moved.forEach { (position, domain) -> move(dsl, rows.getValue(domain).id!!, -1 - position, now) }
                moved.forEach { (position, domain) -> move(dsl, rows.getValue(domain).id!!, position, now) }
                domains.forEachIndexed { position, domain -> if (domain !in rows) insertDomain(dsl, name, domain, position, now, owner) }
                if (!unchanged) {
                    dsl
                        .update(S3_BUCKETS)
                        .set(S3_BUCKETS.UPDATED_AT, now)
                        .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
                        .execute()
                }
                BucketDomainsReplaceResult.Replaced
            }
        } catch (_: IntegrityConstraintViolationException) {
            // the bucket is there, so it is the unique index of the live domains
            BucketDomainsReplaceResult.DomainTaken
        }

    // a new key pair of a live bucket, the one before stops working; false when there is no
    // such bucket or another bucket has the access key id
    fun replaceKeyPair(
        name: String,
        accessKeyId: String,
        secretAccessKey: String,
    ): Boolean =
        try {
            database.dsl
                .update(S3_BUCKETS)
                .set(S3_BUCKETS.ACCESS_KEY_ID, accessKeyId)
                .set(S3_BUCKETS.SECRET_ACCESS_KEY, secretAccessKey)
                .set(S3_BUCKETS.UPDATED_AT, clock.instant())
                .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
                .execute() > 0
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    // the bucket and its domains stay as deleted, its name stays taken; false when there was none
    fun delete(name: String): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val deleted =
                dsl
                    .update(S3_BUCKETS)
                    .set(S3_BUCKETS.DELETED_AT, now)
                    .where(S3_BUCKETS.NAME.eq(name), S3_BUCKETS.DELETED_AT.isNull)
                    .execute() > 0
            if (deleted) {
                dsl
                    .update(S3_BUCKETS_DOMAINS)
                    .set(S3_BUCKETS_DOMAINS.DELETED_AT, now)
                    .where(S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name), S3_BUCKETS_DOMAINS.DELETED_AT.isNull)
                    .execute()
            }
            deleted
        }

    private fun insertDomain(
        dsl: DSLContext,
        name: String,
        domain: String,
        position: Int,
        now: Instant,
        owner: String,
    ) {
        dsl
            .insertInto(S3_BUCKETS_DOMAINS)
            .set(S3_BUCKETS_DOMAINS.DOMAIN, domain)
            .set(S3_BUCKETS_DOMAINS.BUCKET_NAME, name)
            .set(S3_BUCKETS_DOMAINS.POSITION, position)
            .set(S3_BUCKETS_DOMAINS.CREATED_AT, now)
            .set(S3_BUCKETS_DOMAINS.OWNER, owner)
            .execute()
    }

    private fun move(
        dsl: DSLContext,
        id: Int,
        position: Int,
        now: Instant,
    ) {
        dsl
            .update(S3_BUCKETS_DOMAINS)
            .set(S3_BUCKETS_DOMAINS.POSITION, position)
            .set(S3_BUCKETS_DOMAINS.UPDATED_AT, now)
            .where(S3_BUCKETS_DOMAINS.ID.eq(id))
            .execute()
    }

    // the domains that are not deleted, of one bucket, or of all when name is null
    private fun domainsByBucket(
        dsl: DSLContext,
        name: String?,
    ): Map<String, List<String>> =
        dsl
            .selectFrom(S3_BUCKETS_DOMAINS)
            .where(
                S3_BUCKETS_DOMAINS.DELETED_AT.isNull,
                if (name == null) DSL.noCondition() else S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name),
            ).orderBy(S3_BUCKETS_DOMAINS.BUCKET_NAME, S3_BUCKETS_DOMAINS.POSITION)
            .fetch()
            .groupBy({ it.bucketName!! }, { it.domain!! })

    private fun S3BucketsRecord.toBucket(domains: List<String>) =
        S3Bucket(
            name = name!!,
            resourceId = ResourceId(resourceId!!),
            accessKeyId = accessKeyId!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
            domains = domains,
            owner = owner!!,
        )
}

// domains are where caddy serves the bucket's objects, in their order; the secret of the key pair
// is left out, only secretAccessKey and keyPair of the repository read it
data class S3Bucket(
    val name: String,
    // names its objects on the data volume (MODEL-8)
    val resourceId: ResourceId,
    val accessKeyId: String,
    val createdAt: Instant,
    // null until the bucket is changed
    val updatedAt: Instant? = null,
    val domains: List<String> = emptyList(),
    // who created it (MODEL-5)
    val owner: String,
)
