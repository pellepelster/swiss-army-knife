package io.pelle.sak.model.s3

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.S3BucketsRecord
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS_DOMAINS
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Instant
import java.time.Clock

// the database access of the buckets and their domains, only S3BucketService (sak-backend) uses
// it
class S3BucketRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
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

    // the bucket served at the domain
    fun bucketOfDomain(domain: String): String? =
        database.dsl
            .select(S3_BUCKETS_DOMAINS.BUCKET_NAME)
            .from(S3_BUCKETS_DOMAINS)
            .where(S3_BUCKETS_DOMAINS.DOMAIN.eq(domain), S3_BUCKETS_DOMAINS.DELETED_AT.isNull)
            .fetchOne(S3_BUCKETS_DOMAINS.BUCKET_NAME)

    // null when the name, also the one of a deleted bucket, or one of the domains is taken; the
    // secret of the key pair is kept with the secrets
    fun insert(
        name: String,
        accessKeyId: String,
        domains: List<String> = emptyList(),
    ): S3Bucket? =
        try {
            val bucket = S3Bucket(name, accessKeyId, clock.instant(), null, domains)
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                dsl
                    .insertInto(S3_BUCKETS)
                    .set(S3_BUCKETS.NAME, bucket.name)
                    .set(S3_BUCKETS.ACCESS_KEY_ID, bucket.accessKeyId)
                    .set(S3_BUCKETS.CREATED_AT, bucket.createdAt)
                    .execute()
                bucket.domains.forEachIndexed { position, domain -> insertDomain(dsl, bucket.name, domain, position, bucket.createdAt) }
            }
            bucket
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the domains that stay keep their row and take their new position, the ones removed are
    // deleted, the ones added get a row of their own; false when one of the domains belongs to
    // another bucket
    fun replaceDomains(
        name: String,
        domains: List<String>,
    ): Boolean =
        try {
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                val now = clock.instant()
                val rows =
                    dsl
                        .selectFrom(S3_BUCKETS_DOMAINS)
                        .where(S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name), S3_BUCKETS_DOMAINS.DELETED_AT.isNull)
                        .fetch()
                        .associateBy { it.domain!! }
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
                domains.forEachIndexed { position, domain -> if (domain !in rows) insertDomain(dsl, name, domain, position, now) }
                dsl
                    .update(S3_BUCKETS)
                    .set(S3_BUCKETS.UPDATED_AT, now)
                    .where(S3_BUCKETS.NAME.eq(name))
                    .execute()
            }
            true
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
    ) {
        dsl
            .insertInto(S3_BUCKETS_DOMAINS)
            .set(S3_BUCKETS_DOMAINS.DOMAIN, domain)
            .set(S3_BUCKETS_DOMAINS.BUCKET_NAME, name)
            .set(S3_BUCKETS_DOMAINS.POSITION, position)
            .set(S3_BUCKETS_DOMAINS.CREATED_AT, now)
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
            accessKeyId = accessKeyId!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
            domains = domains,
        )
}

// domains are where caddy serves the bucket's objects, in their order; the secret of the key pair
// is one of the secrets
data class S3Bucket(
    val name: String,
    val accessKeyId: String,
    val createdAt: Instant,
    // null until the bucket is changed
    val updatedAt: Instant? = null,
    val domains: List<String> = emptyList(),
)
