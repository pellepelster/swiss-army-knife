package io.pelle.sak.backend.hosting

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

// an upload with no part for this long is unfinished
val STALE_UPLOAD_AGE: Duration = Duration.ofDays(1)

// how often the unfinished uploads are looked for
val STALE_UPLOAD_CHECK: Duration = Duration.ofHours(1)

// removes the multipart uploads of every bucket with no part for STALE_UPLOAD_AGE, so a bucket key
// can not fill the data volume with parts it never completes; changed hears of each bucket that lost
// some, their bytes leave its statistics. S3BucketService runs it
internal fun removeStaleUploads(
    storage: BinaryStorageS3Backend,
    buckets: S3BucketService,
    clock: Clock = Clock.systemUTC(),
    changed: (bucket: String) -> Unit,
) {
    val before = clock.instant().minus(STALE_UPLOAD_AGE)
    buckets.all().forEach { bucket ->
        val removed = storage.removeStaleUploads(bucket.name, before)
        if (removed > 0) {
            logger.info { "removed $removed unfinished uploads of the bucket ${bucket.name}" }
            changed(bucket.name)
        }
    }
}
