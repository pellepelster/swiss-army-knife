package io.pelle.sak.s3.storage

import io.pelle.sak.storage.model.BlobPath

// whether the name can be one of a bucket: 3 to 63 lower case letters, digits and hyphens, starting and
// ending with a letter or digit. A valid s3 bucket name without dots, so it is also a label of a domain,
// the bucket's subdomain. The one rule: who creates buckets asks it, the storage refuses every other name
fun isValidBucketName(name: String): Boolean = BUCKET_NAME.matches(name)

private val BUCKET_NAME = Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$")

fun interface S3BucketPathResolver {
    fun resolve(bucket: String): BlobPath?
}
