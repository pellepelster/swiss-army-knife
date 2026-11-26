package io.pelle.sak.backend.restic

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3Endpoint
import io.pelle.sak.model.ResourceId
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.api.storage.model.internal.BlobPath
import io.pelle.sak.storage.FilesystemLocalStorage
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

// where the files of the repositories are and which of them count, the same on every BlobStore: here in a
// bucket, against the S3 api of SAK itself, where the repositories are below backend/data/restic. The
// blobs themselves, whole or not at all, are the contract of a BlobStore (BlobStoreContract)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlobResticFileStorageTest {
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
                    s3(LocalStorageS3Backend(FilesystemLocalStorage(rootDir), { bucket -> BlobPath(listOf(bucket)) }, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)), access)
                }
            }
        }

    private lateinit var storage: BlobResticFileStorage

    @BeforeAll
    fun start() {
        server.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        // two objects a page, so a listing has more than one
        storage = BlobResticFileStorage.s3(S3Bucket(S3Endpoint("http://localhost:$port/s3", "fsn1"), "the-bucket", "the-deployment", "the-access-key", "the/secret+key"), pageSize = 2)
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

        // the size it removed, then none (ST-7)
        storage.delete(repository, file) shouldBe 10L
        storage.delete(repository, file) shouldBe null
        storage.size(repository, file) shouldBe null
        storage.exists(repository) shouldBe false
        storage.leftovers(setOf(known)).contains(repository.value) shouldBe false
    }
}
