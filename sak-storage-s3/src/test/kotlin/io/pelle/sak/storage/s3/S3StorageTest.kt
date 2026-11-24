package io.pelle.sak.storage.s3

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.api.StorageUsage
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.storage.model.BlobPath
import io.pelle.sak.storage.model.EntityTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random

// the objects of a storage in a bucket, against the S3 api of SAK itself: an S3 storage in the
// process, which checks the signature of each request
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageTest {
    private val rootDir: Path = Files.createTempDirectory("s3-storage-test")

    private val access =
        object : S3AccessCallback {
            override fun resolveSecretKey(
                accessKeyId: String,
                bucket: String?,
            ) = "the/secret+key".takeIf { accessKeyId == "the-access-key" }

            override fun checkAccess(
                accessKeyId: String,
                bucket: String,
                operation: S3Operation,
                key: String?,
            ) = S3AccessResult.GRANTED
        }

    private val server =
        embeddedServer(Netty, port = 0) {
            routing {
                route("/s3") {
                    s3(LocalStorageS3Backend(FilesystemLocalStorage(rootDir), { bucket -> BlobPath(listOf(bucket)) }, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)), access)
                }
            }
        }

    private lateinit var bucket: S3Bucket

    @BeforeAll
    fun start() {
        server.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        bucket = S3Bucket(S3Endpoint("http://localhost:$port/s3", "fsn1"), "the-bucket", "the-deployment", "the-access-key", "the/secret+key")
    }

    @AfterAll
    fun stop() {
        server.stop(0, 0)
        rootDir.toFile().deleteRecursively()
    }

    private var next = 0

    // a storage below a root of its own for each test; two objects a page, so a listing has more than one
    private fun storage() = S3Storage(bucket, listOf("tests", "root${next++}"), pageSize = 2)

    private fun sha256(content: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    private fun S3Storage.put(
        key: List<String>,
        content: ByteArray,
    ) = stage(key, content.inputStream(), sha256(content), Long.MAX_VALUE)!!.commit()

    private fun S3Storage.read(
        key: List<String>,
        offset: Long,
        length: Long,
        size: Long,
    ) = openRange(key, offset, length, size)?.use { it.readAllBytes() }

    @Test
    fun `an object is there once it is committed, and is read whole and in ranges`() {
        val storage = storage()
        val key = listOf("a", "file")
        val content = "the content of a file".toByteArray()
        storage.size(key) shouldBe null

        val staged = storage.stage(key, content.inputStream(), sha256(content), Long.MAX_VALUE)!!
        staged.size shouldBe content.size
        // not in its place yet
        storage.size(key) shouldBe null
        staged.commit()
        staged.discard()

        storage.size(key) shouldBe content.size
        val size = content.size.toLong()
        storage.read(key, 0, size, size) shouldBe content
        storage.read(key, 4, 7, size)!!.decodeToString() shouldBe "content"
        // clamped to the object
        storage.read(key, 15, 100, size)!!.decodeToString() shouldBe "a file"
        storage.read(key, size, 10, size) shouldBe ByteArray(0)
        // another size than the one taken before: the object changed
        storage.read(key, 0, size, size + 1) shouldBe null
        storage.read(listOf("none"), 0, 10, 10) shouldBe null
    }

    @Test
    fun `an object larger than a part is sent in parts and is whole`() {
        val storage = storage()
        val key = listOf("large")
        val content = Random(1).nextBytes(11 * 1024 * 1024 + 123)

        storage.put(key, content)

        storage.size(key) shouldBe content.size
        val size = content.size.toLong()
        sha256(storage.read(key, 0, size, size)!!) shouldBe sha256(content)
        // over the border of two parts
        val part = S3Storage.PART_BYTES.toLong()
        storage.read(key, part - 2, 4, size) shouldBe content.copyOfRange(S3Storage.PART_BYTES - 2, S3Storage.PART_BYTES + 2)
    }

    @Test
    fun `a content that is not the one of its hash, or too large, or discarded leaves nothing`() {
        val storage = storage()
        val small = "small".toByteArray()
        val large = Random(2).nextBytes(6 * 1024 * 1024)

        listOf(small, large).forEach { content ->
            val key = listOf("object")
            storage.stage(key, content.inputStream(), sha256("another content".toByteArray()), Long.MAX_VALUE) shouldBe null
            storage.size(key) shouldBe null

            shouldThrow<EntityTooLargeException> { storage.stage(key, content.inputStream(), sha256(content), content.size - 1L) }
            storage.stage(key, content.inputStream(), sha256(content), content.size.toLong())!!.discard()
            storage.size(key) shouldBe null
        }
        // without a hash, any content is taken
        storage.stage(listOf("unchecked"), small.inputStream(), null, Long.MAX_VALUE)!!.commit()
        storage.size(listOf("unchecked")) shouldBe small.size

        storage.any(storage.prefix("object")) shouldBe false
    }

    @Test
    fun `the objects are listed over pages, counted and found below a prefix, deleted once`() {
        val root = "root$next"
        val storage = storage()
        (1..5).forEach { storage.put(listOf("data", "d$it"), "data $it".toByteArray()) }
        storage.put(listOf("keys", "k"), "a key".toByteArray())
        storage.put(listOf("config"), "the config".toByteArray())

        val listed = mutableListOf<Pair<String, Long>>()
        storage.list(storage.prefix("data")) { key, size ->
            listed.add(key.substringAfterLast('/') to size)
            true
        }
        listed shouldContainExactlyInAnyOrder (1..5).map { "d$it" to 6L }
        // a visit that says false ends the listing
        var visits = 0
        storage.list(storage.prefix("data")) { _, _ -> visits++ < 0 }
        visits shouldBe 1

        storage.any(storage.prefix("keys")) shouldBe true
        storage.any(storage.prefix("locks")) shouldBe false
        storage.children(storage.prefix()) shouldBe setOf("data", "keys")
        storage.bytes(storage.prefix("data")) shouldBe 5 * 6
        // all of the root, counted live on each call
        storage.storageUsage() shouldBe StorageUsage(5 * 6 + 5 + 10)

        storage.delete(listOf("keys", "k")) shouldBe true
        storage.delete(listOf("keys", "k")) shouldBe false
        storage.any(storage.prefix("keys")) shouldBe false
        storage.storageUsage() shouldBe StorageUsage(5 * 6 + 10)
        // every object is below the deployment and the root
        storage.place.endsWith("/the-bucket/the-deployment/tests/$root") shouldBe true
    }
}
