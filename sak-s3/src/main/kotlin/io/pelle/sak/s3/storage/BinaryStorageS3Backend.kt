package io.pelle.sak.s3.storage

import java.io.InputStream
import java.io.SequenceInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.UUID
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

class BinaryStorageS3Backend(
    private val storage: BinaryStorage,
    private val resolver: S3BucketPathResolver,
) : S3StorageBackend {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private data class MetaFile(
        val etag: String,
        val contentType: String? = null,
        val cacheControl: String? = null,
        val contentEncoding: String? = null,
        val userMetadata: Map<String, String> = emptyMap(),
    )

    private data class UploadMetaFile(
        val key: String,
        val meta: MetaFile,
    )

    override fun putObject(
        bucket: String,
        key: String,
        content: InputStream,
        metadata: ObjectMetadata,
    ): PutResult {
        val bucketPath = requireBucket(bucket)
        val digest = MessageDigest.getInstance("MD5")

        storage.write(dataPath(bucketPath, key), DigestInputStream(content, digest))
        val etag = digest.digest().toHex()

        writeMeta(
            bucketPath,
            key,
            MetaFile(etag, metadata.contentType, metadata.cacheControl, metadata.contentEncoding, metadata.userMetadata),
        )

        return PutResult(etag)
    }

    override fun getObject(
        bucket: String,
        key: String,
    ): StoredObject? {
        val info = headObject(bucket, key) ?: return null
        val dataPath = dataPath(requireBucket(bucket), key)
        return StoredObject(info) { offset, length ->
            storage.openRange(dataPath, offset, length) ?: throw NoSuchElementException("object '$key' vanished")
        }
    }

    override fun headObject(
        bucket: String,
        key: String,
    ): StoredObjectInfo? {
        val bucketPath = requireBucket(bucket)
        val blob = storage.stat(dataPath(bucketPath, key)) ?: return null
        return info(bucketPath, key, blob.size, blob.lastModified)
    }

    override fun deleteObject(
        bucket: String,
        key: String,
    ) {
        val bucketPath = requireBucket(bucket)
        storage.delete(dataPath(bucketPath, key))
        storage.delete(metaPath(bucketPath, key))
    }

    override fun listObjects(
        bucket: String,
        prefix: String?,
        delimiter: String?,
        startAfter: String?,
        maxKeys: Int,
    ): ObjectListing {
        val bucketPath = requireBucket(bucket)

        val entries =
            storage
                .list(bucketPath.resolve(DATA))
                .asSequence()
                .filter { prefix == null || it.path.startsWith(prefix) }
                .filter { startAfter == null || it.path > startAfter }

        val objects = mutableListOf<StoredObjectInfo>()
        val commonPrefixes = sortedSetOf<String>()
        var truncated = false
        var lastKey: String? = null

        for (entry in entries) {
            val key = entry.path
            val rolledUp =
                if (delimiter != null) {
                    val rest = key.substring(prefix?.length ?: 0)
                    val delimiterIndex = rest.indexOf(delimiter)
                    if (delimiterIndex >= 0) (prefix ?: "") + rest.substring(0, delimiterIndex + delimiter.length) else null
                } else {
                    null
                }

            if (rolledUp != null && rolledUp in commonPrefixes) {
                lastKey = key
                continue
            }

            if (objects.size + commonPrefixes.size >= maxKeys) {
                truncated = true
                break
            }

            if (rolledUp != null) {
                commonPrefixes.add(rolledUp)
            } else {
                objects.add(info(bucketPath, key, entry.size, entry.lastModified))
            }
            lastKey = key
        }

        return ObjectListing(
            objects = objects,
            commonPrefixes = commonPrefixes.toList(),
            isTruncated = truncated,
            nextContinuationToken = if (truncated) encodeToken(lastKey!!) else null,
        )
    }

    override fun createMultipartUpload(
        bucket: String,
        key: String,
        metadata: ObjectMetadata,
    ): String {
        val bucketPath = requireBucket(bucket)
        requireValidKey(key)

        val uploadId = UUID.randomUUID().toString()
        val meta =
            UploadMetaFile(
                key,
                MetaFile("", metadata.contentType, metadata.cacheControl, metadata.contentEncoding, metadata.userMetadata),
            )
        storage.write(uploadPath(bucketPath, uploadId).resolve(UPLOAD_META), mapper.writeValueAsBytes(meta).inputStream())
        return uploadId
    }

    override fun uploadPart(
        bucket: String,
        uploadId: String,
        partNumber: Int,
        content: InputStream,
    ): String {
        val bucketPath = requireBucket(bucket)
        val uploadPath = requireUpload(bucketPath, uploadId)
        if (partNumber < 1) throw InvalidPartException("part number must be >= 1")

        val digest = MessageDigest.getInstance("MD5")
        storage.write(uploadPath.resolve(partFileName(partNumber)), DigestInputStream(content, digest))
        val etag = digest.digest().toHex()
        storage.write(uploadPath.resolve("${partFileName(partNumber)}.md5"), etag.toByteArray().inputStream())
        return etag
    }

    override fun completeMultipartUpload(
        bucket: String,
        uploadId: String,
        parts: List<PartRef>,
    ): PutResult {
        val bucketPath = requireBucket(bucket)
        val uploadPath = requireUpload(bucketPath, uploadId)
        val uploadMeta =
            storage.open(uploadPath.resolve(UPLOAD_META))?.use { mapper.readValue<UploadMetaFile>(it) }
                ?: throw NoSuchUploadException(uploadId)

        if (parts.isEmpty()) throw InvalidPartException("part list must not be empty")
        parts.zipWithNext().forEach { (a, b) ->
            if (b.partNumber <= a.partNumber) throw InvalidPartException("part numbers must be ascending")
        }

        val partDigest = MessageDigest.getInstance("MD5")
        parts.forEach { part ->
            val partPath = uploadPath.resolve(partFileName(part.partNumber))
            if (storage.stat(partPath) == null) throw InvalidPartException("part ${part.partNumber} was not uploaded")
            val storedEtag =
                storage.open(uploadPath.resolve("${partFileName(part.partNumber)}.md5"))?.use { String(it.readBytes()) }
                    ?: throw InvalidPartException("part ${part.partNumber} was not uploaded")
            if (storedEtag != part.etag.trim('"')) throw InvalidPartException("part ${part.partNumber} etag mismatch")
            partDigest.update(storedEtag.hexToBytes())
        }

        val partStreams =
            Collections.enumeration(
                parts.map { part ->
                    storage.open(uploadPath.resolve(partFileName(part.partNumber)))
                        ?: throw InvalidPartException("part ${part.partNumber} was not uploaded")
                },
            )

        val key = uploadMeta.key
        val etag = "${partDigest.digest().toHex()}-${parts.size}"

        SequenceInputStream(partStreams).use { storage.write(dataPath(bucketPath, key), it) }
        writeMeta(bucketPath, key, uploadMeta.meta.copy(etag = etag))
        storage.deleteTree(uploadPath)

        return PutResult(etag)
    }

    override fun abortMultipartUpload(
        bucket: String,
        uploadId: String,
    ) {
        storage.deleteTree(requireUpload(requireBucket(bucket), uploadId))
    }

    private fun info(
        bucketPath: BlobPath,
        key: String,
        size: Long,
        lastModified: java.time.Instant,
    ): StoredObjectInfo {
        val meta = readMeta(bucketPath, key)
        return StoredObjectInfo(
            key = key,
            etag = meta?.etag ?: "",
            size = size,
            lastModified = lastModified,
            metadata = ObjectMetadata(meta?.contentType, meta?.cacheControl, meta?.contentEncoding, meta?.userMetadata ?: emptyMap()),
        )
    }

    private fun writeMeta(
        bucketPath: BlobPath,
        key: String,
        meta: MetaFile,
    ) {
        storage.write(metaPath(bucketPath, key), mapper.writeValueAsBytes(meta).inputStream())
    }

    private fun readMeta(
        bucketPath: BlobPath,
        key: String,
    ): MetaFile? = storage.open(metaPath(bucketPath, key))?.use { mapper.readValue<MetaFile>(it) }

    private fun requireBucket(bucket: String): BlobPath {
        if (!bucket.matches(BUCKET_NAME_REGEX)) throw NoSuchBucketException(bucket)
        return resolver.resolve(bucket) ?: throw NoSuchBucketException(bucket)
    }

    private fun dataPath(
        bucketPath: BlobPath,
        key: String,
    ) = bucketPath.resolve(DATA).resolve(requireValidKey(key))

    private fun metaPath(
        bucketPath: BlobPath,
        key: String,
    ): BlobPath {
        val segments = requireValidKey(key)
        return bucketPath.resolve(META).resolve(segments.dropLast(1) + "${segments.last()}.json")
    }

    private fun uploadPath(
        bucketPath: BlobPath,
        uploadId: String,
    ) = bucketPath.resolve(UPLOADS, uploadId)

    private fun requireUpload(
        bucketPath: BlobPath,
        uploadId: String,
    ): BlobPath {
        if (!uploadId.matches(UPLOAD_ID_REGEX)) throw NoSuchUploadException(uploadId)
        val uploadPath = uploadPath(bucketPath, uploadId)
        if (storage.stat(uploadPath.resolve(UPLOAD_META)) == null) throw NoSuchUploadException(uploadId)
        return uploadPath
    }

    private fun requireValidKey(key: String): List<String> {
        if (key.isEmpty()) throw InvalidKeyException(key)
        val segments = key.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.contains(NUL) }) throw InvalidKeyException(key)
        return segments
    }

    private fun partFileName(partNumber: Int) = "part-%05d".format(partNumber)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val DATA = "data"
        private const val META = "meta"
        private const val UPLOADS = "uploads"
        private const val UPLOAD_META = "meta.json"

        private val NUL: Char = Char(0)

        private val BUCKET_NAME_REGEX = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        private val UPLOAD_ID_REGEX = Regex("[a-f0-9-]{36}")

        fun encodeToken(key: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())

        fun decodeToken(token: String): String? =
            try {
                String(Base64.getUrlDecoder().decode(token))
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
