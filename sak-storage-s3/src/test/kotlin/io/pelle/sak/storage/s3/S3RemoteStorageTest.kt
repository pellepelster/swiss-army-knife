package io.pelle.sak.storage.s3

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.model.internal.BlobPath
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant

// what the S3 api of SAK does not answer, against a storage that answers as the S3 storage of a provider
class S3RemoteStorageTest {
    // answers ListMultipartUploads in two pages, and 204 to an abort; what it got is in requests
    private fun withStorage(block: (S3RemoteStorage, MutableList<String>) -> Unit) {
        val requests = mutableListOf<String>()
        val server =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    val uri = URLDecoder.decode(exchange.requestURI.toString(), Charsets.UTF_8)
                    requests.add("${exchange.requestMethod} $uri")
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    if (exchange.requestMethod == "GET" && "uploads" in query) {
                        val body =
                            if ("key-marker" !in query) {
                                page(listOf("old" to "u1" to "2026-11-20T10:00:00.000Z", "new" to "u2" to "2026-11-24T09:00:00.000Z"), next = "new" to "u2")
                            } else {
                                page(listOf("older" to "u3" to "2026-11-19T10:00:00.000Z"), next = null)
                            }.toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    } else {
                        exchange.sendResponseHeaders(204, -1)
                    }
                    exchange.close()
                }
                start()
            }
        try {
            val bucket = S3Bucket(S3Endpoint("http://localhost:${server.address.port}", "fsn1"), "the-bucket", "the-deployment", "the-access-key", "the-secret-key")
            block(S3RemoteStorage(bucket, BlobPath(listOf("backend", "data", "restic"))), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun page(
        uploads: List<Pair<Pair<String, String>, String>>,
        next: Pair<String, String>?,
    ) = buildString {
        append("<ListMultipartUploadsResult><Bucket>the-bucket</Bucket>")
        uploads.forEach { (keyAndId, initiated) ->
            append("<Upload><Key>the-deployment/backend/data/restic/${keyAndId.first}</Key><UploadId>${keyAndId.second}</UploadId><Initiated>$initiated</Initiated></Upload>")
        }
        if (next != null) {
            append("<IsTruncated>true</IsTruncated><NextKeyMarker>the-deployment/backend/data/restic/${next.first}</NextKeyMarker><NextUploadIdMarker>${next.second}</NextUploadIdMarker>")
        } else {
            append("<IsTruncated>false</IsTruncated>")
        }
        append("</ListMultipartUploadsResult>")
    }

    @Test
    fun `the uploads below the root that started before are aborted, over every page of the listing (ST-2)`() {
        withStorage { storage, requests ->
            storage.abortStale(BlobPath(emptyList()), Instant.parse("2026-11-24T00:00:00Z")) shouldBe 2

            requests.filter { it.startsWith("GET") }.size shouldBe 2
            requests.first().contains("prefix=the-deployment/backend/data/restic/") shouldBe true
            requests.filter { it.startsWith("GET") }.last().contains("key-marker=the-deployment/backend/data/restic/new") shouldBe true
            requests.filter { it.startsWith("DELETE") } shouldBe
                listOf(
                    "DELETE /the-bucket/the-deployment/backend/data/restic/old?uploadId=u1",
                    "DELETE /the-bucket/the-deployment/backend/data/restic/older?uploadId=u3",
                )
        }
    }

    @Test
    fun `a storage that answers a range with the whole object is refused, unless the range is all of it (ST-9)`() {
        val content = "the whole object".toByteArray()
        val server =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/") { exchange ->
                    // the Range header is ignored
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.use { it.write(content) }
                    exchange.close()
                }
                start()
            }
        try {
            val bucket = S3Bucket(S3Endpoint("http://localhost:${server.address.port}", "fsn1"), "the-bucket", "the-deployment", "the-access-key", "the-secret-key")
            val storage = S3RemoteStorage(bucket, BlobPath(listOf("root")))
            val path = BlobPath(listOf("object"))

            shouldThrow<IOException> { storage.openRange(path, 0, 2) }
            shouldThrow<IOException> { storage.openRange(path, 4, content.size - 1L) }
            storage.openRange(path, 0, content.size - 1L)!!.let { range ->
                range.total shouldBe content.size
                range.content.use { it.readAllBytes() } shouldBe content
            }
        } finally {
            server.stop(0)
        }
    }
}
