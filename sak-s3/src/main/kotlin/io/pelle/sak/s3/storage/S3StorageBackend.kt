package io.pelle.sak.s3.storage

import java.io.InputStream
import java.time.Instant

data class ObjectMetadata(
    val contentType: String? = null,
    val cacheControl: String? = null,
    val contentEncoding: String? = null,
    val userMetadata: Map<String, String> = emptyMap(),
)

data class StoredObjectInfo(
    val key: String,
    val etag: String,
    val size: Long,
    val lastModified: Instant,
    val metadata: ObjectMetadata,
)

data class StoredObject(
    val info: StoredObjectInfo,
    private val opener: (offset: Long, length: Long) -> InputStream,
) {
    fun open(): InputStream = opener(0, info.size)

    fun open(
        offset: Long,
        length: Long,
    ): InputStream = opener(offset, length)
}

data class PutResult(
    val etag: String,
)

data class ObjectListing(
    val objects: List<StoredObjectInfo>,
    val commonPrefixes: List<String>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
)

data class PartRef(
    val partNumber: Int,
    val etag: String,
)

class NoSuchBucketException(
    val bucket: String,
) : RuntimeException("no such bucket '$bucket'")

class NoSuchUploadException(
    val uploadId: String,
) : RuntimeException("upload '$uploadId' does not exist")

class InvalidKeyException(
    val key: String,
) : RuntimeException("invalid object key '$key'")

// a segment of the key longer than the file system takes as a name
class KeyTooLongException(
    val key: String,
) : RuntimeException("the object key '$key' has a part longer than the storage takes")

// s3 allows an object 'a' next to 'a/b', the filesystem layout does not
class KeyConflictException(
    val key: String,
) : RuntimeException("object key '$key' conflicts with an existing object or prefix")

class InvalidPartException(
    message: String,
) : RuntimeException(message)

// the sizes S3 has
object S3Limits {
    // the largest object of one request and the largest part
    const val MAX_OBJECT_BYTES = 5L * 1024 * 1024 * 1024

    // the largest object made of parts
    const val MAX_MULTIPART_OBJECT_BYTES = 5L * 1024 * 1024 * 1024 * 1024
}

interface S3StorageBackend {
    fun putObject(
        bucket: String,
        key: String,
        content: InputStream,
        metadata: ObjectMetadata,
    ): PutResult

    fun getObject(
        bucket: String,
        key: String,
    ): StoredObject?

    fun headObject(
        bucket: String,
        key: String,
    ): StoredObjectInfo?

    fun deleteObject(
        bucket: String,
        key: String,
    )

    fun listObjects(
        bucket: String,
        prefix: String?,
        delimiter: String?,
        startAfter: String?,
        maxKeys: Int,
    ): ObjectListing

    fun createMultipartUpload(
        bucket: String,
        key: String,
        metadata: ObjectMetadata,
    ): String

    // an upload belongs to the key it was created for, every other key is NoSuchUpload
    fun uploadPart(
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        content: InputStream,
    ): String

    fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<PartRef>,
    ): PutResult

    fun abortMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
    )
}
