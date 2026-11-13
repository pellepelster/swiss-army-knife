package io.pelle.sak.s3.storage

import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.BlobPathConflictException
import io.pelle.sak.storage.model.BlobUsage
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Enumeration
import java.util.UUID
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

// the buckets in the binary storage, each below its path (by default buckets/<name>): an object is
// the blob of its key below data/ of the bucket, its metadata a sidecar (<key>.json) below meta/
class BinaryStorageS3Backend(
    private val storage: BinaryStorage,
    private val resolver: S3BucketPathResolver = S3BucketPathResolver { bucket -> BlobPath(listOf(BUCKETS, bucket)) },
    // the room a write may take, by default from the free bytes of the volume of the storage
    private val guard: WriteGuard = WriteGuard({ usableBytes(storage) }),
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
        guard.requireRoom()
        val digest = MessageDigest.getInstance("MD5")

        val etag =
            conflictsAsKeyConflict(key) {
                storage.write(dataPath(bucketPath, key), DigestInputStream(guard.guarded(content), digest))
                digest.digest().toHex()
            }

        try {
            conflictsAsKeyConflict(key) {
                writeMeta(
                    bucketPath,
                    key,
                    MetaFile(etag, metadata.contentType, metadata.cacheControl, metadata.contentEncoding, metadata.userMetadata),
                )
            }
        } catch (e: KeyConflictException) {
            // the sidecar of 'a' ('a.json') collides with the sidecars below 'a.json/', the data
            // is not kept without its sidecar
            storage.delete(dataPath(bucketPath, key))
            throw e
        }

        return PutResult(etag)
    }

    override fun getObject(
        bucket: String,
        key: String,
    ): StoredObject? {
        val info = headObject(bucket, key) ?: return null
        val dataPath = dataPath(requireBucket(bucket), key)
        return StoredObject(info) { offset, length ->
            storage.openRange(dataPath, offset, length, info.size) ?: throw NoSuchElementException("object '$key' vanished")
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
        // s3 answers max-keys=0 with an empty listing that is not truncated
        if (maxKeys <= 0) return ObjectListing(emptyList(), emptyList(), isTruncated = false, nextContinuationToken = null)

        // only the directory the prefix points into is walked ('a/b/c' -> 'a/b/'), not the bucket
        val prefixDirectory = prefix?.substringBeforeLast('/', "")?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        val listed =
            try {
                storage.list(bucketPath.resolve(DATA).resolve(prefixDirectory))
            } catch (_: IllegalArgumentException) {
                // a prefix with an invalid path segment ('..') matches no key
                emptyList()
            }

        val entries =
            listed
                .asSequence()
                .map { it.copy(segments = prefixDirectory + it.segments) }
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
        guard.requireRoom()

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
        key: String,
        uploadId: String,
        partNumber: Int,
        content: InputStream,
    ): String {
        val bucketPath = requireBucket(bucket)
        val uploadPath = requireUpload(bucketPath, key, uploadId).path
        if (partNumber !in 1..MAX_PART_NUMBER) throw InvalidPartException("a part number is from 1 to $MAX_PART_NUMBER")
        guard.requireRoom()

        val digest = MessageDigest.getInstance("MD5")
        storage.write(uploadPath.resolve(partFileName(partNumber)), DigestInputStream(guard.guarded(content), digest))
        val etag = digest.digest().toHex()
        storage.write(uploadPath.resolve("${partFileName(partNumber)}.md5"), etag.toByteArray().inputStream())
        return etag
    }

    override fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<PartRef>,
    ): PutResult {
        val bucketPath = requireBucket(bucket)
        val (uploadPath, uploadMeta) = requireUpload(bucketPath, key, uploadId)

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

        // the parts are opened one after the other as the sequence stream advances, opening
        // all of them up front costs a file descriptor per part (up to 10000)
        val partStreams =
            object : Enumeration<InputStream> {
                private val remaining = parts.iterator()

                override fun hasMoreElements() = remaining.hasNext()

                override fun nextElement(): InputStream {
                    val part = remaining.next()
                    return storage.open(uploadPath.resolve(partFileName(part.partNumber)))
                        ?: throw InvalidPartException("part ${part.partNumber} was not uploaded")
                }
            }

        val etag = "${partDigest.digest().toHex()}-${parts.size}"

        // the object is written before its parts go, it takes their room once more
        guard.requireRoom()
        conflictsAsKeyConflict(key) {
            SequenceInputStream(partStreams).use { storage.write(dataPath(bucketPath, key), guard.guarded(it, WriteGuard.MAX_MULTIPART_OBJECT_BYTES)) }
            writeMeta(bucketPath, key, uploadMeta.meta.copy(etag = etag))
        }
        storage.deleteTree(uploadPath)

        return PutResult(etag)
    }

    override fun abortMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
    ) {
        storage.deleteTree(requireUpload(requireBucket(bucket), key, uploadId).path)
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

    // the objects of the bucket and their size, with the parts of the uploads not completed yet,
    // which take their room too; staged writes are not in it
    fun usage(bucket: String): BlobUsage {
        val bucketPath = requireBucket(bucket)
        val objects = storage.usage(bucketPath.resolve(DATA))
        // the parts, not the metadata and the md5 of each
        val parts =
            storage
                .list(bucketPath.resolve(UPLOADS))
                .filter { it.segments.last().startsWith(PART_PREFIX) && !it.segments.last().endsWith(".md5") }
                .sumOf { it.size }
        return BlobUsage(objects.blobs, objects.bytes + parts)
    }

    // removes the uploads of the bucket that were neither completed nor aborted and had no part or
    // start since before, so they do not keep their room; the number of uploads removed
    fun removeStaleUploads(
        bucket: String,
        before: java.time.Instant,
    ): Int {
        val uploads = requireBucket(bucket).resolve(UPLOADS)
        return storage
            .list(uploads)
            .groupBy { it.segments.first() }
            .filterValues { files -> files.maxOf { it.lastModified }.isBefore(before) }
            .keys
            .onEach { uploadId -> storage.deleteTree(uploads.resolve(uploadId)) }
            .size
    }

    // the directory on disk the objects of the bucket are in, each at its key, for a reader that
    // serves them from there (caddy, the exception of MODEL-7)
    fun objectsDirectory(bucket: String): Path = storage.directory(objectsPath(bucket))

    private fun objectsPath(bucket: String): BlobPath = requireBucket(bucket).resolve(DATA)

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
        return bucketPath.resolve(META).resolve(segments.dropLast(1) + "${segments.last()}$SIDECAR_SUFFIX")
    }

    private fun uploadPath(
        bucketPath: BlobPath,
        uploadId: String,
    ) = bucketPath.resolve(UPLOADS, uploadId)

    private data class Upload(
        val path: BlobPath,
        val meta: UploadMetaFile,
    )

    private fun requireUpload(
        bucketPath: BlobPath,
        key: String,
        uploadId: String,
    ): Upload {
        if (!uploadId.matches(UPLOAD_ID_REGEX)) throw NoSuchUploadException(uploadId)
        val uploadPath = uploadPath(bucketPath, uploadId)
        val meta =
            storage.open(uploadPath.resolve(UPLOAD_META))?.use { mapper.readValue<UploadMetaFile>(it) }
                ?: throw NoSuchUploadException(uploadId)
        if (meta.key != key) throw NoSuchUploadException(uploadId)
        return Upload(uploadPath, meta)
    }

    private fun <T> conflictsAsKeyConflict(
        key: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: BlobPathConflictException) {
            throw KeyConflictException(key)
        }

    // keys map to paths, so a key ending with '/' (a directory marker on s3) is invalid here
    private fun requireValidKey(key: String): List<String> {
        if (key.isEmpty()) throw InvalidKeyException(key)
        val segments = key.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.contains(NUL) }) throw InvalidKeyException(key)
        // a segment is the name of a file, the last one also of its sidecar (<name>.json)
        val bytes = segments.map { it.toByteArray(Charsets.UTF_8).size }
        if (bytes.any { it > BlobPath.MAX_SEGMENT_BYTES } || bytes.last() + SIDECAR_SUFFIX.length > BlobPath.MAX_SEGMENT_BYTES) {
            throw KeyTooLongException(key)
        }
        return segments
    }

    private fun partFileName(partNumber: Int) = "$PART_PREFIX%05d".format(partNumber)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val DATA = "data"

        // where the buckets are, unless a resolver says otherwise
        private const val BUCKETS = "buckets"
        private const val META = "meta"
        private const val UPLOADS = "uploads"

        private const val PART_PREFIX = "part-"

        // the largest number of a part, as S3 has it
        const val MAX_PART_NUMBER = 10_000
        private const val UPLOAD_META = "meta.json"

        // the sidecar of an object, its name with this
        private const val SIDECAR_SUFFIX = ".json"

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

// the bytes free on the volume the storage is on, from its root or the nearest directory above it
// that is there
private fun usableBytes(storage: BinaryStorage): Long {
    var dir: java.nio.file.Path? = storage.directory(BlobPath(emptyList()))
    while (dir != null && !java.nio.file.Files.exists(dir)) dir = dir.parent
    return dir?.let { java.nio.file.Files.getFileStore(it).usableSpace } ?: Long.MAX_VALUE
}
