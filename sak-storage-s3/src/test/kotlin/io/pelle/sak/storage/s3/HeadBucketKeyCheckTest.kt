package io.pelle.sak.storage.s3

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.api.storage.model.internal.BlobPath
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.WriteGuard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration

class HeadBucketKeyCheckTest {
    @TempDir
    lateinit var tempDir: Path

    private val check = HeadBucketKeyCheck(Duration.ofSeconds(5))

    @Test
    fun `asks for the bucket once and tells what the storage answers`() {
        // what the storage got: the request line and the headers of each request
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var status = 200
        val server =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    requests.add("${exchange.requestMethod} ${exchange.requestURI}" to exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() })
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                }
                start()
            }
        try {
            val endpoint = S3Endpoint("http://localhost:${server.address.port}", "fsn1")
            val answers =
                mapOf(
                    200 to S3KeyStatus.VALID,
                    401 to S3KeyStatus.KEYS_REFUSED,
                    403 to S3KeyStatus.KEYS_REFUSED,
                    404 to S3KeyStatus.NO_BUCKET,
                    // the bucket is in another region
                    301 to S3KeyStatus.NO_BUCKET,
                    307 to S3KeyStatus.NO_BUCKET,
                    500 to S3KeyStatus.UNKNOWN,
                    503 to S3KeyStatus.UNKNOWN,
                )
            answers.forEach { (answer, expected) ->
                status = answer
                check.check(endpoint, "the-bucket", "the access key", "the secret key") shouldBe expected
            }

            // once each, also after an answer the client would ask again for
            requests.map { it.first } shouldBe List(answers.size) { "HEAD /the-bucket" }
            val headers = requests.first().second
            headers.getValue("authorization") shouldStartWith "AWS4-HMAC-SHA256 Credential=the access key/"
            headers.getValue("authorization").contains("/fsn1/s3/aws4_request") shouldBe true
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a storage that does not answer is not known`() {
        val port = HttpServer.create(InetSocketAddress("localhost", 0), 0).let { server -> server.address.port.also { server.stop(0) } }
        check.check(S3Endpoint("http://localhost:$port", "fsn1"), "the-bucket", "the access key", "the secret key") shouldBe S3KeyStatus.UNKNOWN
    }

    // the S3 api of SAK checks the signature of a request like any S3 storage does. It has no
    // HeadBucket: a request it takes the signature of is answered 501, another one 403
    @Test
    fun `signs the request with the keys, as an S3 storage checks it`() {
        val access =
            object : S3AccessCallback {
                override fun resolveSecretKey(
                    accessKeyId: String,
                    bucket: String?,
                ) = if (accessKeyId == "the-access-key") "the/secret+key" else null

                override fun checkAccess(
                    accessKeyId: String,
                    bucket: String,
                    operation: S3Operation,
                    key: String?,
                ) = S3AccessResult.GRANTED
            }
        val server =
            embeddedServer(Netty, port = 0) {
                routing {
                    route("/s3") {
                        s3(LocalStorageS3Backend(FilesystemLocalStorage(tempDir), { bucket -> BlobPath(listOf(bucket)) }, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)), access)
                    }
                }
            }
        server.start()
        try {
            val endpoint = S3Endpoint("http://localhost:${runBlocking { server.engine.resolvedConnectors().first().port }}/s3", "fsn1")

            check.check(endpoint, "the-bucket", "the-access-key", "the/secret+key") shouldBe S3KeyStatus.UNKNOWN
            check.check(endpoint, "the-bucket", "the-access-key", "another/secret+key") shouldBe S3KeyStatus.KEYS_REFUSED
            check.check(endpoint, "the-bucket", "another-access-key", "the/secret+key") shouldBe S3KeyStatus.KEYS_REFUSED
        } finally {
            server.stop(0, 0)
        }
    }
}
