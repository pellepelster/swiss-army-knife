package io.pelle.sak.s3

enum class S3Operation {
    LIST_OBJECTS,
    GET_OBJECT,
    HEAD_OBJECT,
    PUT_OBJECT,
    DELETE_OBJECT,
    CREATE_MULTIPART_UPLOAD,
    UPLOAD_PART,
    COMPLETE_MULTIPART_UPLOAD,
    ABORT_MULTIPART_UPLOAD,
}

enum class S3AccessResult {
    GRANTED,

    DENIED,

    NO_SUCH_BUCKET,
}

// the extension point for credentials and authorization: secrets can depend on the bucket,
// access on the operation and the key
interface S3AccessCallback {
    fun resolveSecretKey(
        accessKeyId: String,
        bucket: String?,
    ): String?

    fun checkAccess(
        accessKeyId: String,
        bucket: String,
        operation: S3Operation,
        key: String?,
    ): S3AccessResult
}
