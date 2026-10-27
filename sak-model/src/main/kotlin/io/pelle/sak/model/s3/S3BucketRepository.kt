package io.pelle.sak.model.s3

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.S3BucketsRecord
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// the database access of the buckets, only S3BucketService uses it
internal class S3BucketRepository(
    private val database: SakDatabase,
) {
    fun all(): List<S3Bucket> =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .orderBy(S3_BUCKETS.NAME)
            .fetch()
            .map { it.toBucket() }

    fun byName(name: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.NAME.eq(name))
            .fetchOne()
            ?.toBucket()

    fun byAccessKeyId(accessKeyId: String): S3Bucket? =
        database.dsl
            .selectFrom(S3_BUCKETS)
            .where(S3_BUCKETS.ACCESS_KEY_ID.eq(accessKeyId))
            .fetchOne()
            ?.toBucket()

    // false when the name is taken
    fun insert(bucket: S3Bucket): Boolean =
        try {
            database.dsl
                .insertInto(S3_BUCKETS)
                .set(S3_BUCKETS.NAME, bucket.name)
                .set(S3_BUCKETS.ACCESS_KEY_ID, bucket.accessKeyId)
                .set(S3_BUCKETS.SECRET_ACCESS_KEY, bucket.secretAccessKey)
                .set(S3_BUCKETS.CREATED_AT, LocalDateTime.ofInstant(bucket.createdAt, ZoneOffset.UTC))
                .execute()
            true
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    private fun S3BucketsRecord.toBucket() =
        S3Bucket(
            name = name!!,
            accessKeyId = accessKeyId!!,
            secretAccessKey = secretAccessKey!!,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
        )
}

data class S3Bucket(
    val name: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val createdAt: Instant,
)
