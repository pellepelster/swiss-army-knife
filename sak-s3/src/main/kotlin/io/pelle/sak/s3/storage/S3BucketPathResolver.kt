package io.pelle.sak.s3.storage

import io.pelle.sak.s3.storage.BlobPath

fun interface S3BucketPathResolver {
    fun resolve(bucket: String): BlobPath?
}
