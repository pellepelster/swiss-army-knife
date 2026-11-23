package io.pelle.sak.backend.restic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.backend.storage.S3Bucket
import io.pelle.sak.backend.storage.S3Endpoint
import io.pelle.sak.model.ResourceId
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.EntityTooLargeException
import io.pelle.sak.storage.FilesystemBinaryStorage
import io.pelle.sak.storage.WriteGuard
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

// the files of the repositories in a bucket, against the S3 api of SAK itself: an S3 storage in the
// process, which checks the signature of each request
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ResticFileStorageTest {
    private val rootDir: Path = Files.createTempDirectory("s3-restic-test")

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
                    s3(BinaryStorageS3Backend(FilesystemBinaryStorage(rootDir), { bucket -> BlobPath(listOf(bucket)) }, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)), access)
                }
            }
        }

    private lateinit var storage: S3ResticFileStorage

    @BeforeAll
    fun start() {
        server.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        // two objects a page, so a listing has more than one
        storage = S3ResticFileStorage(S3Bucket(S3Endpoint("http://localhost:$port/s3", "fsn1"), "the-bucket", "the-deployment", "the-access-key", "the/secret+key"), pageSize = 2)
    }

    @AfterAll
    fun stop() {
        server.stop(0, 0)
        rootDir.toFile().deleteRecursively()
    }

    private var next = 0

    // a repository of its own for each test
    private fun repository() = ResourceId.of("s3test${(next++).toString().padStart(6, '0')}")!!

    private fun sha256(content: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    private fun file(
        type: String,
        content: ByteArray,
    ) = ResticFile(type, sha256(content))

    // a file of the type with the content, in its place
    private fun put(
        repository: ResourceId,
        type: String,
        content: ByteArray,
    ): ResticFile {
        val file = file(type, content)
        storage.stage(repository, file, content.inputStream(), file.name, Long.MAX_VALUE)!!.commit()
        return file
    }

    private fun read(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ) = storage.open(repository, file, offset, length, size)?.use { it.readAllBytes() }

    @Test
    fun `a file is there once it is committed, and is read whole and in ranges`() {
        val repository = repository()
        val content = "the content of a file".toByteArray()
        val file = file("data", content)
        storage.size(repository, file) shouldBe null
        storage.exists(repository) shouldBe false

        val staged = storage.stage(repository, file, content.inputStream(), file.name, Long.MAX_VALUE)!!
        staged.size shouldBe content.size
        // not in its place yet
        storage.size(repository, file) shouldBe null
        staged.commit()
        staged.discard()

        storage.size(repository, file) shouldBe content.size
        storage.exists(repository) shouldBe true
        val size = content.size.toLong()
        read(repository, file, 0, size, size) shouldBe content
        read(repository, file, 4, 7, size)!!.decodeToString() shouldBe "content"
        // clamped to the file
        read(repository, file, 15, 100, size)!!.decodeToString() shouldBe "a file"
        read(repository, file, size, 10, size) shouldBe ByteArray(0)
        // another size than the one taken before: the file changed
        read(repository, file, 0, size, size + 1) shouldBe null
        read(repository, ResticFile("data", "0".repeat(64)), 0, 10, 10) shouldBe null
    }

    @Test
    fun `a file larger than a part is sent in parts and is whole`() {
        val repository = repository()
        val content = Random(1).nextBytes(11 * 1024 * 1024 + 123)

        val file = put(repository, "data", content)

        storage.size(repository, file) shouldBe content.size
        val size = content.size.toLong()
        sha256(read(repository, file, 0, size, size)!!) shouldBe file.name
        // over the border of two parts
        read(repository, file, 5L * 1024 * 1024 - 2, 4, size) shouldBe content.copyOfRange(5 * 1024 * 1024 - 2, 5 * 1024 * 1024 + 2)
        storage.bytes(repository) shouldBe content.size
    }

    @Test
    fun `a content that is not the one of its name, or too large, or discarded leaves nothing`() {
        val repository = repository()
        val small = "small".toByteArray()
        val large = Random(2).nextBytes(6 * 1024 * 1024)

        listOf(small, large).forEach { content ->
            val file = ResticFile("data", sha256("another content".toByteArray()))
            storage.stage(repository, file, content.inputStream(), file.name, Long.MAX_VALUE) shouldBe null
            storage.size(repository, file) shouldBe null

            val right = file("data", content)
            shouldThrow<EntityTooLargeException> { storage.stage(repository, right, content.inputStream(), right.name, content.size - 1L) }
            storage.stage(repository, right, content.inputStream(), right.name, content.size.toLong())!!.discard()
            storage.size(repository, right) shouldBe null
        }

        storage.exists(repository) shouldBe false
        storage.bytes(repository) shouldBe 0
    }

    @Test
    fun `the config has no hash, the files of a type are listed by their names`() {
        val repository = repository()
        val other = repository()
        storage.stage(repository, ResticFile.config, "the config".byteInputStream(), null, Long.MAX_VALUE)!!.commit()
        val data = (1..5).map { put(repository, "data", "data $it".toByteArray()) }
        val key = put(repository, "keys", "a key".toByteArray())
        put(other, "data", "of another repository".toByteArray())

        val listed = mutableListOf<Pair<String, Long>>()
        storage.forEach(repository, "data") { name, size -> listed.add(name to size) }
        listed shouldContainExactlyInAnyOrder data.map { it.name to 6L }
        val keys = mutableListOf<String>()
        storage.forEach(repository, "keys") { name, _ -> keys.add(name) }
        keys shouldBe listOf(key.name)
        storage.forEach(repository, "locks") { _, _ -> error("there is no lock") }

        storage.size(repository, ResticFile.config) shouldBe 10
        // in the bucket where they are on the data volume of a deployment
        val objects = Files.walk(rootDir.resolve("the-bucket")).use { paths -> paths.filter(Files::isRegularFile).map { it.toString() }.toList() }
        val dir = "/the-deployment/backend/data/restic/${repository.value}"
        objects.count { it.endsWith("$dir/config") } shouldBe 1
        objects.count { it.endsWith("$dir/keys/${key.name}") } shouldBe 1
        // every object of the bucket is below the name of the deployment
        objects.all { "/the-deployment/backend/data/restic/" in it } shouldBe true
        storage.bytes(repository) shouldBe 10 + 5 * 6 + 5
        storage.hasFiles(repository, listOf("index", "snapshots")) shouldBe false
        storage.hasFiles(repository, listOf("index", "data")) shouldBe true
    }

    @Test
    fun `a file is deleted once, the repositories without a row are the leftovers`() {
        val repository = repository()
        val known = repository()
        val file = put(repository, "snapshots", "a snapshot".toByteArray())
        put(known, "snapshots", "another snapshot".toByteArray())

        storage.leftovers(setOf(known)).contains(repository.value) shouldBe true
        storage.leftovers(setOf(known)).contains(known.value) shouldBe false

        storage.delete(repository, file) shouldBe true
        storage.delete(repository, file) shouldBe false
        storage.size(repository, file) shouldBe null
        storage.exists(repository) shouldBe false
        storage.leftovers(setOf(known)).contains(repository.value) shouldBe false
    }
}
