package io.pelle.sak.storage.s3

import com.github.davidmoten.aws.lw.client.HttpMethod
import com.github.davidmoten.aws.lw.client.Multipart
import io.pelle.sak.api.storage.RemoteBlobStore
import io.pelle.sak.api.storage.RemotePage
import io.pelle.sak.api.storage.RemoteRange
import io.pelle.sak.api.storage.RemoteStorage
import io.pelle.sak.api.storage.RemoteUpload
import io.pelle.sak.api.storage.model.BlobListEntry
import io.pelle.sak.api.storage.model.BlobPath
import java.io.IOException
import java.time.Instant
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

// the objects below root in a bucket of the S3 storage, each at its path below root: the requests of
// the S3 api (HEAD, a GET of a range, PUT, a multipart upload, DELETE, ListObjectsV2)
class S3RemoteStorage(
    private val bucket: S3Bucket,
    // the path below the prefix of the bucket that all objects of the storage are below
    private val root: BlobPath,
) : RemoteStorage {
    override val place = "s3:${bucket.endpoint.url}/${bucket.name}/${bucket.key(*root.segments.toTypedArray())}"

    override val partBytes = PART_BYTES

    private fun key(path: BlobPath) = bucket.key(*(root.segments + path.segments).toTypedArray())

    private fun request(path: BlobPath) = bucket.request(*(root.segments + path.segments).toTypedArray())

    override fun size(path: BlobPath): Long? {
        val response = request(path).method(HttpMethod.HEAD).response()
        return when (response.statusCode()) {
            200 -> response.firstHeader("Content-Length").map { it.toLong() }.orElseThrow { IOException("the storage answered no size") }
            404 -> null
            else -> throw IOException("the storage answered ${response.statusCode()} for the size of an object")
        }
    }

    override fun openRange(
        path: BlobPath,
        first: Long,
        last: Long,
    ): RemoteRange? {
        val response = request(path).header("Range", "bytes=$first-$last").responseInputStream()
        val total =
            when (response.statusCode()) {
                206 -> response.header("Content-Range").map { it.substringAfterLast('/').toLongOrNull() }.orElse(null)
                // all of it: the range only when it is all of the object (ST-9), else the storage serves
                // no ranges and its answer is not what was asked for
                200 -> {
                    val total = response.header("Content-Length").map { it.toLongOrNull() }.orElse(null)
                    if (first != 0L || total != last + 1) {
                        response.close()
                        throw IOException("the storage answered the whole object of $total bytes for the bytes $first to $last")
                    }
                    total
                }
                404, 416 -> {
                    response.close()
                    return null
                }
                else -> {
                    val status = response.statusCode()
                    response.close()
                    throw IOException("the storage answered $status for an object")
                }
            }
        return RemoteRange(response, total)
    }

    override fun put(
        path: BlobPath,
        content: ByteArray,
    ) {
        request(path).method(HttpMethod.PUT).requestBody(content).execute()
    }

    // a part is sent where it is written, one at a time: a large content is never in memory as a whole
    override fun upload(path: BlobPath): RemoteUpload {
        val stream =
            Multipart
                .s3(bucket.client)
                .bucket(bucket.name)
                .key(key(path))
                .executor(SameThread)
                .partSize(PART_BYTES)
                .partTimeout(5, TimeUnit.MINUTES)
                .outputStream()
        return object : RemoteUpload {
            override fun write(part: ByteArray) = stream.write(part)

            override fun complete() = stream.close()

            override fun abort() = stream.abort()
        }
    }

    override fun delete(path: BlobPath) {
        request(path).method(HttpMethod.DELETE).execute()
    }

    // ListMultipartUploads below the prefix, a page at a time, and an AbortMultipartUpload for each
    // that started before `before`
    override fun abortStale(
        prefix: BlobPath,
        before: Instant,
    ): Int {
        var aborted = 0
        var keyMarker: String? = null
        var uploadMarker: String? = null
        do {
            var request = bucket.bucketRequest().query("uploads").query("prefix", key(prefix) + "/")
            keyMarker?.let { request = request.query("key-marker", it) }
            uploadMarker?.let { request = request.query("upload-id-marker", it) }
            val page = request.responseAsXml()
            page.childrenWithName("Upload").forEach { upload ->
                if (Instant.parse(upload.content("Initiated")).isBefore(before)) {
                    bucket.client
                        .path(bucket.name, *upload.content("Key").split('/').toTypedArray())
                        .query("uploadId", upload.content("UploadId"))
                        .method(HttpMethod.DELETE)
                        .execute()
                    aborted++
                }
            }
            val more = page.content("IsTruncated") == "true"
            keyMarker = page.takeIf { more }?.content("NextKeyMarker")
            uploadMarker = page.takeIf { more }?.content("NextUploadIdMarker")
        } while (keyMarker != null)
        return aborted
    }

    override fun page(
        prefix: BlobPath,
        children: Boolean,
        max: Int?,
        token: String?,
    ): RemotePage {
        val start = key(prefix) + "/"
        var request = bucket.bucketRequest().query("list-type", "2").query("prefix", start)
        if (children) request = request.query("delimiter", "/")
        if (max != null) request = request.query("max-keys", max.toString())
        token?.let { request = request.query("continuation-token", it) }
        val page = request.responseAsXml()
        return RemotePage(
            page.childrenWithName("Contents").map {
                BlobListEntry(it.content("Key").removePrefix(start).split('/'), it.content("Size").toLong(), Instant.parse(it.content("LastModified")))
            },
            page.childrenWithName("CommonPrefixes").map { it.content("Prefix").removePrefix(start).trimEnd('/') },
            page.takeIf { it.content("IsTruncated") == "true" }?.content("NextContinuationToken"),
        )
    }

    // runs a task where it is given
    private object SameThread : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()

        override fun shutdown() = Unit

        override fun shutdownNow(): List<Runnable> = emptyList()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ) = true
    }

    companion object {
        // the size of a part of a multipart upload, the least S3 takes
        const val PART_BYTES = 5 * 1024 * 1024
    }
}

// the blobs below root in the bucket, a store of their own; how many objects a listing asks for at
// once, what the storage gives when pageSize is null
fun s3BlobStore(
    bucket: S3Bucket,
    root: BlobPath,
    pageSize: Int? = null,
) = RemoteBlobStore(S3RemoteStorage(bucket, root), pageSize)
