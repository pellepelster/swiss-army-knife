package io.pelle.sak.s3.storage

import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.model.BlobUsage
import io.pelle.sak.storage.FilesystemBinaryStorage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest

class BinaryStorageS3BackendTest {
    @TempDir
    lateinit var tempDir: Path

    private val storage by lazy { BinaryStorageS3Backend(FilesystemBinaryStorage(tempDir), rootResolver()) }

    private fun rootResolver(root: String = "root1") = S3BucketPathResolver { bucket -> BlobPath(listOf(root, "$bucket-data")) }

    private fun bucketDir(
        bucket: String = "bucket1",
        root: String = "root1",
    ) = tempDir.resolve("$root/$bucket-data")

    private fun md5Hex(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun put(
        key: String,
        content: String = "content of $key",
        metadata: ObjectMetadata = ObjectMetadata(),
    ) = storage.putObject("bucket1", key, content.byteInputStream(), metadata)

    @Test
    fun `counts the objects of a bucket and their size with the parts of unfinished uploads, not the metadata`() {
        storage.usage("bucket1") shouldBe BlobUsage(0, 0)

        put("a.txt", "12345")
        put("dir/sub/b.txt", "123")
        storage.createMultipartUpload("bucket1", "c.txt", ObjectMetadata())

        storage.usage("bucket1") shouldBe BlobUsage(2, 8)

        // the parts take their room before the upload completes (SEC-9)
        val uploadId = storage.createMultipartUpload("bucket1", "d.txt", ObjectMetadata())
        storage.uploadPart("bucket1", "d.txt", uploadId, 1, "1234".byteInputStream())
        storage.usage("bucket1") shouldBe BlobUsage(2, 12)
        storage.abortMultipartUpload("bucket1", "d.txt", uploadId)

        storage.deleteObject("bucket1", "a.txt")
        storage.usage("bucket1") shouldBe BlobUsage(1, 3)
    }

    // SEC-9: one key of a bucket can not fill the data volume
    @Test
    fun `a write starts only above the free bytes kept and stops when they are reached while it streams`() {
        var free = 1000L
        val guarded =
            BinaryStorageS3Backend(
                FilesystemBinaryStorage(tempDir),
                rootResolver(),
                WriteGuard({ free }, minFreeBytes = 500, maxBytes = 100, checkEvery = 10),
            )

        guarded.putObject("bucket1", "ok.txt", "x".repeat(50).byteInputStream(), ObjectMetadata())
        free = 400
        shouldThrow<InsufficientStorageException> { guarded.putObject("bucket1", "no.txt", "x".byteInputStream(), ObjectMetadata()) }
        shouldThrow<InsufficientStorageException> { guarded.createMultipartUpload("bucket1", "no.bin", ObjectMetadata()) }

        // free at the start, not after the first 10 bytes: the write stops, nothing of it stays
        var reads = 0
        free = 1000
        val shrinking =
            object : java.io.InputStream() {
                override fun read(): Int {
                    if (++reads == 15) free = 400
                    return if (reads <= 60) 'x'.code else -1
                }
            }
        shouldThrow<InsufficientStorageException> { guarded.putObject("bucket1", "half.txt", shrinking, ObjectMetadata()) }
        guarded.headObject("bucket1", "half.txt").shouldBeNull()

        // larger than an object may be
        free = 1000
        shouldThrow<EntityTooLargeException> { guarded.putObject("bucket1", "big.txt", "x".repeat(101).byteInputStream(), ObjectMetadata()) }
        guarded.headObject("bucket1", "big.txt").shouldBeNull()
        guarded.headObject("bucket1", "ok.txt").shouldNotBeNull()
    }

    @Test
    fun `a part number is from 1 to 10000`() {
        val uploadId = storage.createMultipartUpload("bucket1", "big.bin", ObjectMetadata())
        storage.uploadPart("bucket1", "big.bin", uploadId, 10_000, "x".byteInputStream())
        shouldThrow<InvalidPartException> { storage.uploadPart("bucket1", "big.bin", uploadId, 10_001, "x".byteInputStream()) }
        shouldThrow<InvalidPartException> { storage.uploadPart("bucket1", "big.bin", uploadId, 0, "x".byteInputStream()) }
    }

    @Test
    fun `an upload without a part or start since before goes, a newer one stays`() {
        val old = storage.createMultipartUpload("bucket1", "old.bin", ObjectMetadata())
        storage.uploadPart("bucket1", "old.bin", old, 1, "x".byteInputStream())
        val cutoff = java.time.Instant.now().plusSeconds(1)

        storage.removeStaleUploads("bucket1", cutoff.minusSeconds(3600)) shouldBe 0
        storage.removeStaleUploads("bucket1", cutoff) shouldBe 1
        shouldThrow<NoSuchUploadException> { storage.uploadPart("bucket1", "old.bin", old, 2, "x".byteInputStream()) }
        storage.removeStaleUploads("bucket1", cutoff) shouldBe 0
    }

    @Test
    fun `putObject stores content and returns md5 etag`() {
        val content = "hello world".toByteArray()

        val result = storage.putObject("bucket1", "greeting.txt", content.inputStream(), ObjectMetadata(contentType = "text/plain"))

        result.etag shouldBe md5Hex(content)

        val stored = storage.getObject("bucket1", "greeting.txt").shouldNotBeNull()
        stored.open().use { it.readBytes() } shouldBe content
        stored.info.etag shouldBe md5Hex(content)
        stored.info.size shouldBe content.size.toLong()
        stored.info.metadata.contentType shouldBe "text/plain"
    }

    @Test
    fun `putObject preserves cache control content encoding and user metadata`() {
        put(
            "asset.js.gz",
            metadata =
                ObjectMetadata(
                    contentType = "application/javascript",
                    cacheControl = "max-age=630720000",
                    contentEncoding = "gzip",
                    userMetadata = mapOf("generator" to "hugo"),
                ),
        )

        val info = storage.headObject("bucket1", "asset.js.gz").shouldNotBeNull()
        info.metadata.cacheControl shouldBe "max-age=630720000"
        info.metadata.contentEncoding shouldBe "gzip"
        info.metadata.userMetadata shouldBe mapOf("generator" to "hugo")
    }

    @Test
    fun `putObject overwrites existing object`() {
        put("key.txt", "first")
        val result = put("key.txt", "second")

        result.etag shouldBe md5Hex("second".toByteArray())
        storage
            .getObject("bucket1", "key.txt")
            .shouldNotBeNull()
            .open()
            .use { String(it.readBytes()) } shouldBe "second"
    }

    @Test
    fun `keys with slashes are stored as nested paths`() {
        put("css/styles/main.css")

        storage.headObject("bucket1", "css/styles/main.css").shouldNotBeNull().key shouldBe "css/styles/main.css"
        bucketDir()
            .resolve("data/css/styles/main.css")
            .toFile()
            .exists()
            .shouldBeTrue()
    }

    @Test
    fun `a key that is an object can not be a prefix and vice versa`() {
        put("file")
        shouldThrow<KeyConflictException> { put("file/child") }
        storage.getObject("bucket1", "file").shouldNotBeNull()

        put("dir/child")
        shouldThrow<KeyConflictException> { put("dir") }
        storage.getObject("bucket1", "dir/child").shouldNotBeNull()
        storage.headObject("bucket1", "dir").shouldBeNull()

        // the metadata sidecar of 'a' lives at 'a.json', clashing with sidecars below 'a.json/'
        put("sidecar.json/child")
        shouldThrow<KeyConflictException> { put("sidecar") }
        storage.headObject("bucket1", "sidecar").shouldBeNull()
        // nothing staged is left behind by the failed writes
        tempDir.resolve(BlobPath.STAGING_DIRECTORY).toFile().list().shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `headObject returns null for missing key`() {
        storage.headObject("bucket1", "missing.txt").shouldBeNull()
        storage.getObject("bucket1", "missing.txt").shouldBeNull()
    }

    @Test
    fun `buckets are provisioned on first access`() {
        put("key.txt")
        storage.putObject("otherbucket", "key.txt", "data".byteInputStream(), ObjectMetadata())

        storage.headObject("otherbucket", "key.txt").shouldNotBeNull()
        bucketDir("otherbucket")
            .resolve("data/key.txt")
            .toFile()
            .exists()
            .shouldBeTrue()
    }

    @Test
    fun `invalid bucket names throw NoSuchBucketException`() {
        listOf("UPPER", "a", "-leading", "trailing-", "with/slash", "..", "with space").forEach { bucket ->
            shouldThrow<NoSuchBucketException> { storage.headObject(bucket, "key.txt") }
        }
    }

    // SR-8: a part of a key is the name of a file, the last one also of its sidecar (<name>.json)
    @Test
    fun `a key with a part longer than a name of a file is too long`() {
        put("a".repeat(250))
        put("${"d".repeat(255)}/x")
        shouldThrow<KeyTooLongException> { put("a".repeat(251)) }
        shouldThrow<KeyTooLongException> { put("${"d".repeat(256)}/x") }
    }

    @Test
    fun `traversal keys are rejected`() {
        listOf("../escape", "a/../../escape", "a//b", "a/./b", "", "/leading").forEach { key ->
            shouldThrow<InvalidKeyException> { put(key) }
        }
        tempDir
            .resolve("escape")
            .toFile()
            .exists()
            .shouldBeFalse()
    }

    @Test
    fun `deleteObject removes object and is idempotent`() {
        put("dir/nested/key.txt")

        storage.deleteObject("bucket1", "dir/nested/key.txt")

        storage.headObject("bucket1", "dir/nested/key.txt").shouldBeNull()
        bucketDir()
            .resolve("data/dir")
            .toFile()
            .exists()
            .shouldBeFalse()

        storage.deleteObject("bucket1", "dir/nested/key.txt")
    }

    @Test
    fun `listObjects returns keys sorted with prefix filter`() {
        listOf("b.txt", "a.txt", "sub/c.txt", "sub/a.txt").forEach { put(it) }

        val all = storage.listObjects("bucket1", null, null, null, 1000)
        all.objects.map { it.key } shouldContainExactly listOf("a.txt", "b.txt", "sub/a.txt", "sub/c.txt")
        all.isTruncated.shouldBeFalse()

        val prefixed = storage.listObjects("bucket1", "sub/", null, null, 1000)
        prefixed.objects.map { it.key } shouldContainExactly listOf("sub/a.txt", "sub/c.txt")
    }

    @Test
    fun `listObjects with delimiter rolls up common prefixes`() {
        listOf("index.html", "css/main.css", "css/extra.css", "js/app.js").forEach { put(it) }

        val listing = storage.listObjects("bucket1", null, "/", null, 1000)

        listing.objects.map { it.key } shouldContainExactly listOf("index.html")
        listing.commonPrefixes shouldContainExactly listOf("css/", "js/")
    }

    @Test
    fun `listObjects paginates with continuation token`() {
        ('a'..'e').forEach { put("$it.txt") }

        val page1 = storage.listObjects("bucket1", null, null, null, 2)
        page1.objects.map { it.key } shouldContainExactly listOf("a.txt", "b.txt")
        page1.isTruncated.shouldBeTrue()

        val startAfter = BinaryStorageS3Backend.decodeToken(page1.nextContinuationToken.shouldNotBeNull())
        val page2 = storage.listObjects("bucket1", null, null, startAfter, 2)
        page2.objects.map { it.key } shouldContainExactly listOf("c.txt", "d.txt")

        val page3 = storage.listObjects("bucket1", null, null, BinaryStorageS3Backend.decodeToken(page2.nextContinuationToken!!), 2)
        page3.objects.map { it.key } shouldContainExactly listOf("e.txt")
        page3.isTruncated.shouldBeFalse()
        page3.nextContinuationToken.shouldBeNull()
    }

    @Test
    fun `listObjects with a nested prefix lists below it only`() {
        listOf("a/b/one.txt", "a/b/two.txt", "a/bc.txt", "a/b/c/three.txt", "x.txt").forEach { put(it) }

        storage.listObjects("bucket1", "a/b/", null, null, 1000).objects.map { it.key } shouldContainExactly
            listOf("a/b/c/three.txt", "a/b/one.txt", "a/b/two.txt")
        storage.listObjects("bucket1", "a/b", null, null, 1000).objects.map { it.key } shouldContainExactly
            listOf("a/b/c/three.txt", "a/b/one.txt", "a/b/two.txt", "a/bc.txt")
        storage.listObjects("bucket1", "a/b/t", null, null, 1000).objects.map { it.key } shouldContainExactly listOf("a/b/two.txt")
        storage.listObjects("bucket1", "a/b/", "/", null, 1000).commonPrefixes shouldContainExactly listOf("a/b/c/")
        storage.listObjects("bucket1", "missing/", null, null, 1000).objects.shouldBeEmpty()
        storage.listObjects("bucket1", "../", null, null, 1000).objects.shouldBeEmpty()
    }

    @Test
    fun `listObjects with max keys 0 returns an empty untruncated listing`() {
        put("a.txt")

        val listing = storage.listObjects("bucket1", null, null, null, 0)

        listing.objects.shouldBeEmpty()
        listing.isTruncated.shouldBeFalse()
        listing.nextContinuationToken.shouldBeNull()
    }

    @Test
    fun `listObjects does not leak the metadata sidecars`() {
        put("index.html")

        storage.listObjects("bucket1", null, null, null, 1000).objects.map { it.key } shouldContainExactly listOf("index.html")
    }

    @Test
    fun `multipart upload concatenates parts and returns composite etag`() {
        val part1 = "a".repeat(1024).toByteArray()
        val part2 = "b".repeat(1024).toByteArray()

        val uploadId = storage.createMultipartUpload("bucket1", "big.bin", ObjectMetadata(contentType = "application/octet-stream"))
        val etag1 = storage.uploadPart("bucket1", "big.bin", uploadId, 1, part1.inputStream())
        val etag2 = storage.uploadPart("bucket1", "big.bin", uploadId, 2, part2.inputStream())

        etag1 shouldBe md5Hex(part1)
        etag2 shouldBe md5Hex(part2)

        val result = storage.completeMultipartUpload("bucket1", "big.bin", uploadId, listOf(PartRef(1, etag1), PartRef(2, etag2)))

        val partDigests = MessageDigest.getInstance("MD5").digest(md5Hex(part1).hexToBytes() + md5Hex(part2).hexToBytes())
        result.etag shouldBe "${partDigests.joinToString("") { "%02x".format(it) }}-2"

        val stored = storage.getObject("bucket1", "big.bin").shouldNotBeNull()
        stored.open().use { it.readBytes() } shouldBe part1 + part2
        stored.info.metadata.contentType shouldBe "application/octet-stream"
        bucketDir()
            .resolve("uploads/$uploadId")
            .toFile()
            .exists()
            .shouldBeFalse()
    }

    @Test
    fun `completeMultipartUpload rejects wrong etag and unordered parts`() {
        val uploadId = storage.createMultipartUpload("bucket1", "big.bin", ObjectMetadata())
        val etag1 = storage.uploadPart("bucket1", "big.bin", uploadId, 1, "one".byteInputStream())
        val etag2 = storage.uploadPart("bucket1", "big.bin", uploadId, 2, "two".byteInputStream())

        shouldThrow<InvalidPartException> {
            storage.completeMultipartUpload("bucket1", "big.bin", uploadId, listOf(PartRef(1, "wrong")))
        }
        shouldThrow<InvalidPartException> {
            storage.completeMultipartUpload("bucket1", "big.bin", uploadId, listOf(PartRef(2, etag2), PartRef(1, etag1)))
        }
        shouldThrow<InvalidPartException> {
            storage.completeMultipartUpload("bucket1", "big.bin", uploadId, emptyList())
        }
    }

    @Test
    fun `abortMultipartUpload removes upload state`() {
        val uploadId = storage.createMultipartUpload("bucket1", "big.bin", ObjectMetadata())
        storage.uploadPart("bucket1", "big.bin", uploadId, 1, "data".byteInputStream())

        storage.abortMultipartUpload("bucket1", "big.bin", uploadId)

        bucketDir()
            .resolve("uploads/$uploadId")
            .toFile()
            .exists()
            .shouldBeFalse()
    }

    @Test
    fun `an upload only accepts its own key`() {
        val uploadId = storage.createMultipartUpload("bucket1", "big.bin", ObjectMetadata())
        val etag = storage.uploadPart("bucket1", "big.bin", uploadId, 1, "data".byteInputStream())

        shouldThrow<NoSuchUploadException> { storage.uploadPart("bucket1", "other.bin", uploadId, 2, "data".byteInputStream()) }
        shouldThrow<NoSuchUploadException> { storage.completeMultipartUpload("bucket1", "other.bin", uploadId, listOf(PartRef(1, etag))) }
        shouldThrow<NoSuchUploadException> { storage.abortMultipartUpload("bucket1", "other.bin", uploadId) }
        storage.headObject("bucket1", "other.bin").shouldBeNull()

        storage.completeMultipartUpload("bucket1", "big.bin", uploadId, listOf(PartRef(1, etag)))
        storage.getObject("bucket1", "big.bin").shouldNotBeNull()
    }

    @Test
    fun `unknown uploadId throws NoSuchUploadException`() {
        shouldThrow<NoSuchUploadException> {
            storage.uploadPart("bucket1", "big.bin", "00000000-0000-0000-0000-000000000000", 1, "data".byteInputStream())
        }
        shouldThrow<NoSuchUploadException> {
            storage.uploadPart("bucket1", "big.bin", "../../escape", 1, "data".byteInputStream())
        }
    }

    @Test
    fun `the resolver decides where a bucket is stored`() {
        val other = BinaryStorageS3Backend(FilesystemBinaryStorage(tempDir), rootResolver("root2"))

        storage.putObject("bucket1", "index.html", "one".byteInputStream(), ObjectMetadata())
        other.putObject("bucket1", "index.html", "two".byteInputStream(), ObjectMetadata())

        bucketDir(root = "root1")
            .resolve("data/index.html")
            .toFile()
            .readText() shouldBe "one"
        bucketDir(root = "root2")
            .resolve("data/index.html")
            .toFile()
            .readText() shouldBe "two"
    }

    @Test
    fun `a bucket the resolver does not know does not exist`() {
        val empty = BinaryStorageS3Backend(FilesystemBinaryStorage(tempDir), { null })

        shouldThrow<NoSuchBucketException> { empty.putObject("bucket1", "key.txt", "x".byteInputStream(), ObjectMetadata()) }
        shouldThrow<NoSuchBucketException> { empty.headObject("bucket1", "key.txt") }
    }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
