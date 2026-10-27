package io.pelle.sak.backend

import io.pelle.sak.model.s3.S3BucketService
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation

// every bucket has a key pair of its own, which may access that bucket only
class BucketKeysAccessCallback(
    private val buckets: S3BucketService,
) : S3AccessCallback {
    override fun resolveSecretKey(
        accessKeyId: String,
        bucket: String?,
    ) = buckets.byAccessKeyId(accessKeyId)?.secretAccessKey

    override fun checkAccess(
        accessKeyId: String,
        bucket: String,
        operation: S3Operation,
        key: String?,
    ) = if (buckets.byAccessKeyId(accessKeyId)?.name == bucket) S3AccessResult.GRANTED else S3AccessResult.DENIED
}
