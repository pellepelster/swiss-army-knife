package io.pelle.sak.model.s3

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.S3BucketsRecord
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS_DOMAINS
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Instant

// the database access of the buckets and their domains, only S3BucketService (sak-backend) uses
// it
class S3BucketRepository(
    private val database: SakDatabase,
) {
    fun all(): List<S3Bucket> {
        val domains = domainsByBucket(database.dsl, null)
        return database.dsl
            .selectFrom(S3_BUCKETS)
            .orderBy(S3_BUCKETS.NAME)
            .fetch()
            .map { it.toBucket(domains[it.name].orEmpty()) }
    }

    fun byName(name: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.NAME.eq(name))
            .fetchOne()
            ?.let { it.toBucket(domainsByBucket(database.dsl, name)[name].orEmpty()) }

    fun byAccessKeyId(accessKeyId: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.ACCESS_KEY_ID.eq(accessKeyId))
            .fetchOne()
            ?.let { it.toBucket(domainsByBucket(database.dsl, it.name)[it.name].orEmpty()) }

    // the bucket served at the domain
    fun bucketOfDomain(domain: String): String? =
        database.dsl
            .select(S3_BUCKETS_DOMAINS.BUCKET_NAME)
            .from(S3_BUCKETS_DOMAINS)
            .where(S3_BUCKETS_DOMAINS.DOMAIN.eq(domain))
            .fetchOne(S3_BUCKETS_DOMAINS.BUCKET_NAME)

    // false when the name or one of the domains is taken
    fun insert(bucket: S3Bucket): Boolean =
        try {
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                dsl
                    .insertInto(S3_BUCKETS)
                    .set(S3_BUCKETS.NAME, bucket.name)
                    .set(S3_BUCKETS.ACCESS_KEY_ID, bucket.accessKeyId)
                    .set(S3_BUCKETS.SECRET_ACCESS_KEY, bucket.secretAccessKey)
                    .set(S3_BUCKETS.CREATED_AT, bucket.createdAt)
                    .execute()
                insertDomains(dsl, bucket.name, bucket.domains)
            }
            true
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    // false when one of the domains belongs to another bucket
    fun replaceDomains(
        name: String,
        domains: List<String>,
    ): Boolean =
        try {
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                dsl.deleteFrom(S3_BUCKETS_DOMAINS).where(S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name)).execute()
                insertDomains(dsl, name, domains)
            }
            true
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    private fun insertDomains(
        dsl: DSLContext,
        name: String,
        domains: List<String>,
    ) = domains.forEachIndexed { position, domain ->
        dsl
            .insertInto(S3_BUCKETS_DOMAINS)
            .set(S3_BUCKETS_DOMAINS.DOMAIN, domain)
            .set(S3_BUCKETS_DOMAINS.BUCKET_NAME, name)
            .set(S3_BUCKETS_DOMAINS.POSITION, position)
            .execute()
    }

    // of one bucket, or of all when name is null
    private fun domainsByBucket(
        dsl: DSLContext,
        name: String?,
    ): Map<String, List<String>> =
        dsl
            .selectFrom(S3_BUCKETS_DOMAINS)
            .where(if (name == null) DSL.noCondition() else S3_BUCKETS_DOMAINS.BUCKET_NAME.eq(name))
            .orderBy(S3_BUCKETS_DOMAINS.BUCKET_NAME, S3_BUCKETS_DOMAINS.POSITION)
            .fetch()
            .groupBy({ it.bucketName!! }, { it.domain!! })

    private fun S3BucketsRecord.toBucket(domains: List<String>) =
        S3Bucket(
            name = name!!,
            accessKeyId = accessKeyId!!,
            secretAccessKey = secretAccessKey!!,
            createdAt = createdAt!!,
            domains = domains,
        )
}

// domains are where caddy serves the bucket's objects, in their order
data class S3Bucket(
    val name: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val createdAt: Instant,
    val domains: List<String> = emptyList(),
)
