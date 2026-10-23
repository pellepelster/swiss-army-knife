package io.pelle.sak.s3.api

class S3Exception(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message) {
    companion object {
        fun noSuchKey(key: String) = S3Exception(404, "NoSuchKey", "the specified key '$key' does not exist")

        fun noSuchBucket(bucket: String) = S3Exception(404, "NoSuchBucket", "the specified bucket '$bucket' does not exist")

        fun accessDenied() = S3Exception(403, "AccessDenied", "access denied")

        fun invalidRequest(message: String) = S3Exception(400, "InvalidRequest", message)
    }
}
