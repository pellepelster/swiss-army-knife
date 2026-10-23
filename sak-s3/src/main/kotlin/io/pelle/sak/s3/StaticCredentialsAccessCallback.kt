package io.pelle.sak.s3

class StaticCredentialsAccessCallback(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val buckets: Set<String>,
) : S3AccessCallback {
    override fun resolveSecretKey(
        accessKeyId: String,
        bucket: String?,
    ) = if (accessKeyId == this.accessKeyId) secretAccessKey else null

    override fun checkAccess(
        accessKeyId: String,
        bucket: String,
        operation: S3Operation,
        key: String?,
    ) = when {
        accessKeyId != this.accessKeyId -> S3AccessResult.DENIED
        bucket !in buckets -> S3AccessResult.NO_SUCH_BUCKET
        else -> S3AccessResult.GRANTED
    }
}
