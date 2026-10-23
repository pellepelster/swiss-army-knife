package io.pelle.sak.s3

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.BlobPath
import io.pelle.sak.s3.storage.FilesystemBinaryStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3WireTest {
    private val rootDir: Path = Files.createTempDirectory("s3-wire-test")

    private val accessCallback =
        object : S3AccessCallback {
            override fun resolveSecretKey(
                accessKeyId: String,
                bucket: String?,
            ) = if (accessKeyId == ACCESS_KEY_ID) SECRET_KEY else null

            override fun checkAccess(
                accessKeyId: String,
                bucket: String,
                operation: S3Operation,
                key: String?,
            ): S3AccessResult =
                when {
                    bucket == MISSING_BUCKET -> S3AccessResult.NO_SUCH_BUCKET
                    bucket == FORBIDDEN_BUCKET -> S3AccessResult.DENIED
                    else -> S3AccessResult.GRANTED
                }
        }

    private val server =
        embeddedServer(Netty, port = 0) {
            routing {
                route("/s3") {
                    s3(
                        BinaryStorageS3Backend(FilesystemBinaryStorage(rootDir)) { bucket -> BlobPath(listOf(bucket)) },
                        accessCallback,
                    )
                }
            }
        }

    private var port: Int = 0

    @BeforeAll
    fun start() {
        server.start()
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun client(
        accessKeyId: String = ACCESS_KEY_ID,
        secretKey: String = SECRET_KEY,
    ): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create("http://localhost:$port/s3"))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretKey)))
            .build()

    @Test
    fun `putObject stores an object and returns its md5 etag`() {
        val response =
            client().putObject(
                {
                    it
                        .bucket("bucket1")
                        .key("hello.txt")
                        .contentType("text/plain")
                        .cacheControl("max-age=60")
                },
                RequestBody.fromString("hello wire"),
            )

        response.eTag() shouldBe "\"3eeebf6b04a9821afe3b68830b7038f9\""
    }

    @Test
    fun `putObject on an already existing key overwrites the object`() {
        fun put(content: String) =
            client().putObject(
                {
                    it
                        .bucket("bucket1")
                        .key("redeployed/index.html")
                        .contentType("text/html")
                        .metadata(mapOf("md5chksum" to "aGFzaC1vZi0kY29udGVudA=="))
                },
                RequestBody.fromString(content),
            )

        put("first version")
        val overwrite = put("second version, a different length")

        overwrite.eTag() shouldBe "\"5f5cd9b5e3b15278ac57a49317bddc2a\""

        val stored = client().getObject { it.bucket("bucket1").key("redeployed/index.html") }
        String(stored.readAllBytes()) shouldBe "second version, a different length"
        stored.response().metadata() shouldBe mapOf("md5chksum" to "aGFzaC1vZi0kY29udGVudA==")
    }

    @Test
    fun `responses carry the object's cache headers and no no-cache defaults`() {
        client().putObject(
            { it.bucket("bucket1").key("cached.css").cacheControl("max-age=3600") },
            RequestBody.fromString("cached"),
        )

        val response = client().getObject { it.bucket("bucket1").key("cached.css") }
        val headerNames =
            response
                .response()
                .sdkHttpResponse()
                .headers()
                .keys
                .map { it.lowercase() }

        headerNames.contains("expires").shouldBeFalse()
        headerNames.contains("pragma").shouldBeFalse()
        response.response().cacheControl() shouldBe "max-age=3600"
    }

    @Test
    fun `getObject roundtrips content and metadata`() {
        client().putObject(
            {
                it
                    .bucket("bucket1")
                    .key("roundtrip.css")
                    .contentType("text/css")
                    .cacheControl("max-age=630720000")
            },
            RequestBody.fromString("body { color: red }"),
        )

        val response = client().getObject { it.bucket("bucket1").key("roundtrip.css") }

        String(response.readAllBytes()) shouldBe "body { color: red }"
        response.response().contentType() shouldBe "text/css"
        response.response().cacheControl() shouldBe "max-age=630720000"
        response.response().contentLength() shouldBe 19L
    }

    @Test
    fun `getObject with a range returns only the requested bytes`() {
        client().putObject({ it.bucket("bucket1").key("ranged.txt") }, RequestBody.fromString("0123456789"))

        fun get(range: String) = client().getObject { it.bucket("bucket1").key("ranged.txt").range(range) }

        get("bytes=2-5").use {
            String(it.readAllBytes()) shouldBe "2345"
            it.response().contentRange() shouldBe "bytes 2-5/10"
            it.response().contentLength() shouldBe 4L
        }
        get("bytes=7-").use { String(it.readAllBytes()) shouldBe "789" }
        get("bytes=-3").use { String(it.readAllBytes()) shouldBe "789" }
        get("bytes=8-100").use { String(it.readAllBytes()) shouldBe "89" }

        val exception = shouldThrow<S3Exception> { get("bytes=10-12") }
        exception.statusCode() shouldBe 416
        exception.awsErrorDetails().errorCode() shouldBe "InvalidRange"
    }

    @Test
    fun `headObject returns metadata without body`() {
        client().putObject(
            { it.bucket("bucket1").key("stat/me.txt").contentType("text/plain") },
            RequestBody.fromString("stat me"),
        )

        val response = client().headObject { it.bucket("bucket1").key("stat/me.txt") }

        response.contentLength() shouldBe 7L
        response.contentType() shouldBe "text/plain"
        response.eTag().isNotBlank().shouldBeTrue()
    }

    @Test
    fun `getObject on a missing key fails with NoSuchKey`() {
        val exception =
            shouldThrow<S3Exception> {
                client().getObject { it.bucket("bucket1").key("does/not/exist.txt") }
            }

        exception.statusCode() shouldBe 404
        exception.awsErrorDetails().errorCode() shouldBe "NoSuchKey"
    }

    @Test
    fun `listObjectsV2 lists keys with prefixes and paginates`() {
        val bucket = "listbucket"
        listOf("index.html", "css/a.css", "css/b.css", "js/app.js", "posts/one/index.html").forEach { key ->
            client().putObject({ it.bucket(bucket).key(key) }, RequestBody.fromString("content of $key"))
        }

        val all = client().listObjectsV2 { it.bucket(bucket) }
        all.contents().map { it.key() } shouldContainExactly
            listOf("css/a.css", "css/b.css", "index.html", "js/app.js", "posts/one/index.html")
        all.isTruncated.shouldBeFalse()

        val prefixed = client().listObjectsV2 { it.bucket(bucket).prefix("css/") }
        prefixed.contents().map { it.key() } shouldContainExactly listOf("css/a.css", "css/b.css")

        val delimited = client().listObjectsV2 { it.bucket(bucket).delimiter("/") }
        delimited.contents().map { it.key() } shouldContainExactly listOf("index.html")
        delimited.commonPrefixes().map { it.prefix() } shouldContainExactly listOf("css/", "js/", "posts/")

        val page1 = client().listObjectsV2 { it.bucket(bucket).maxKeys(2) }
        page1.contents().map { it.key() } shouldContainExactly listOf("css/a.css", "css/b.css")
        page1.isTruncated.shouldBeTrue()

        val page2 = client().listObjectsV2 { it.bucket(bucket).maxKeys(2).continuationToken(page1.nextContinuationToken()) }
        page2.contents().map { it.key() } shouldContainExactly listOf("index.html", "js/app.js")

        val page3 = client().listObjectsV2 { it.bucket(bucket).maxKeys(2).continuationToken(page2.nextContinuationToken()) }
        page3.contents().map { it.key() } shouldContainExactly listOf("posts/one/index.html")
        page3.isTruncated.shouldBeFalse()
    }

    @Test
    fun `listObjects etags match the objects md5`() {
        client().putObject({ it.bucket("etagbucket").key("data.bin") }, RequestBody.fromString("etag content"))

        val listed = client().listObjectsV2 { it.bucket("etagbucket") }.contents().single()
        val head = client().headObject { it.bucket("etagbucket").key("data.bin") }

        listed.eTag() shouldBe head.eTag()
        listed.size() shouldBe 12L
    }

    @Test
    fun `deleteObject removes the object and is idempotent`() {
        client().putObject({ it.bucket("bucket1").key("victim.txt") }, RequestBody.fromString("delete me"))

        client().deleteObject { it.bucket("bucket1").key("victim.txt") }
        client().deleteObject { it.bucket("bucket1").key("victim.txt") }

        shouldThrow<S3Exception> { client().headObject { it.bucket("bucket1").key("victim.txt") } }
            .statusCode() shouldBe 404
    }

    @Test
    fun `multipart upload assembles parts into the final object`() {
        val bucket = "multipartbucket"
        val key = "big/asset.bin"
        val part1 = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val part2 = "final short part".toByteArray()

        val s3 = client()
        val uploadId = s3.createMultipartUpload { it.bucket(bucket).key(key).contentType("application/octet-stream") }.uploadId()

        val etag1 =
            s3
                .uploadPart({
                    it
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(1)
                }, RequestBody.fromBytes(part1))
                .eTag()
        val etag2 =
            s3
                .uploadPart({
                    it
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(2)
                }, RequestBody.fromBytes(part2))
                .eTag()

        val completed =
            s3.completeMultipartUpload {
                it.bucket(bucket).key(key).uploadId(uploadId).multipartUpload(
                    CompletedMultipartUpload
                        .builder()
                        .parts(
                            CompletedPart
                                .builder()
                                .partNumber(1)
                                .eTag(etag1)
                                .build(),
                            CompletedPart
                                .builder()
                                .partNumber(2)
                                .eTag(etag2)
                                .build(),
                        ).build(),
                )
            }

        completed.eTag().endsWith("-2\"").shouldBeTrue()

        val stored = client().getObject { it.bucket(bucket).key(key) }.readAllBytes()
        stored shouldBe part1 + part2
    }

    @Test
    fun `aborted multipart upload leaves no object behind`() {
        val s3 = client()
        val uploadId = s3.createMultipartUpload { it.bucket("bucket1").key("aborted.bin") }.uploadId()
        s3.uploadPart({
            it
                .bucket("bucket1")
                .key("aborted.bin")
                .uploadId(uploadId)
                .partNumber(1)
        }, RequestBody.fromString("part"))

        s3.abortMultipartUpload { it.bucket("bucket1").key("aborted.bin").uploadId(uploadId) }

        shouldThrow<S3Exception> { client().headObject { it.bucket("bucket1").key("aborted.bin") } }
            .statusCode() shouldBe 404
    }

    @Test
    fun `wrong credentials are rejected with SignatureDoesNotMatch`() {
        val exception =
            shouldThrow<S3Exception> {
                client(secretKey = "wrong-secret").listObjectsV2 { it.bucket("bucket1") }
            }

        exception.statusCode() shouldBe 403
        exception.awsErrorDetails().errorCode() shouldBe "SignatureDoesNotMatch"
    }

    @Test
    fun `unknown access keys are rejected with InvalidAccessKeyId`() {
        val exception =
            shouldThrow<S3Exception> {
                client(accessKeyId = "unknown-key").listObjectsV2 { it.bucket("bucket1") }
            }

        exception.statusCode() shouldBe 403
        exception.awsErrorDetails().errorCode() shouldBe "InvalidAccessKeyId"
    }

    @Test
    fun `callback DENIED is signaled as AccessDenied`() {
        val exception =
            shouldThrow<S3Exception> {
                client().putObject({ it.bucket(FORBIDDEN_BUCKET).key("nope.txt") }, RequestBody.fromString("nope"))
            }

        exception.statusCode() shouldBe 403
        exception.awsErrorDetails().errorCode() shouldBe "AccessDenied"
    }

    @Test
    fun `callback NO_SUCH_BUCKET is signaled as NoSuchBucket`() {
        val exception =
            shouldThrow<S3Exception> {
                client().listObjectsV2 { it.bucket(MISSING_BUCKET) }
            }

        exception.statusCode() shouldBe 404
        exception.awsErrorDetails().errorCode() shouldBe "NoSuchBucket"
    }

    companion object {
        private const val ACCESS_KEY_ID = "wire-test-key"
        private const val SECRET_KEY = "wire-test-secret"
        private const val FORBIDDEN_BUCKET = "forbiddenbucket"
        private const val MISSING_BUCKET = "missingbucket"
    }

    @AfterAll
    fun cleanup() {
        server.stop()
        rootDir.toFile().deleteRecursively()
    }
}
