package io.pelle.sak.storage.s3

import io.kotest.matchers.shouldBe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.api.storage.BlobStoreContract
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.api.storage.model.BlobPath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

// the contract of a BlobStore in a bucket, against the S3 api of SAK itself: an S3 storage in the
// process, which checks the signature of each request
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStoreTest : BlobStoreContract() {
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

    // a root of its own for each store; two objects a page, so a listing has more than one
    override fun store() = s3BlobStore(bucket, BlobPath(listOf("tests", "root${next++}")), pageSize = 2)

    @Test
    fun `the objects are below the prefix of the bucket and the root of the store`() {
        val root = "root$next"
        val store = store()
        store.stage(BlobPath(listOf("a", "b")), "x".byteInputStream(), null, Long.MAX_VALUE)!!.commit()

        store.place.endsWith("/the-bucket/the-deployment/tests/$root") shouldBe true
        val objects = Files.walk(rootDir.resolve("the-bucket")).use { paths -> paths.filter(Files::isRegularFile).map { it.toString() }.toList() }
        objects.count { it.endsWith("/the-deployment/tests/$root/a/b") } shouldBe 1
    }
}
