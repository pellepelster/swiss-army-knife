package io.pelle.sak.storage.s3

import com.github.davidmoten.aws.lw.client.HttpMethod
import com.github.davidmoten.aws.lw.client.Multipart
import com.github.davidmoten.aws.lw.client.MultipartOutputStream
import com.github.davidmoten.aws.lw.client.xml.XmlElement
import io.pelle.sak.api.StorageReporter
import io.pelle.sak.api.StorageUsage
import io.pelle.sak.storage.model.EntityTooLargeException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

// a content on its way to an object: committed it is the object, discarded nothing of it is left
interface S3StagedObject {
    val size: Long

    fun commit()

    fun discard()
}

// the objects below root in a bucket of the S3 storage, each at a key of segments below root. An
// object shows whole or not at all: a small content is one PUT, a larger one a multipart upload that
// is completed only when all of it is there and checked. It knows nothing of what it keeps
class S3Storage(
    private val bucket: S3Bucket,
    // the segments below the prefix of the bucket that all objects of the storage are below
    private val root: List<String>,
    // how many objects a listing asks for at once, what the storage gives when null
    private val pageSize: Int? = null,
) : StorageReporter {
    // where the objects are, e.g. for the log
    val place = "s3:${bucket.endpoint.url}/${bucket.name}/${bucket.key(*root.toTypedArray())}"

    // the key of a prefix below root, ending in a slash
    fun prefix(vararg segments: String) = bucket.key(*(root + segments).toTypedArray()) + "/"

    private fun request(key: List<String>) = bucket.request(*(root + key).toTypedArray())

    // the size of the object, null when there is none
    fun size(key: List<String>): Long? {
        val response = request(key).method(HttpMethod.HEAD).response()
        return when (response.statusCode()) {
            200 -> response.firstHeader("Content-Length").map { it.toLong() }.orElseThrow { IOException("the storage answered no size") }
            404 -> null
            else -> throw IOException("the storage answered ${response.statusCode()} for the size of an object")
        }
    }

    // `length` bytes from `offset` of the object of `size`, the caller closes them; null when there is
    // none, or another object took its place since its size was taken
    fun openRange(
        key: List<String>,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? {
        val end = minOf(offset + length, size)
        // nothing to read: a range of no bytes can not be asked for
        if (offset >= end) return if (size(key) == size) ByteArrayInputStream(ByteArray(0)) else null
        val response = request(key).header("Range", "bytes=$offset-${end - 1}").responseInputStream()
        val total =
            when (response.statusCode()) {
                206 -> response.header("Content-Range").map { it.substringAfterLast('/').toLongOrNull() }.orElse(null)
                // all of it, where the storage answers a range of all so
                200 -> response.header("Content-Length").map { it.toLongOrNull() }.orElse(null)?.takeIf { offset == 0L }
                404, 416 -> null
                else -> {
                    val status = response.statusCode()
                    response.close()
                    throw IOException("the storage answered $status for an object")
                }
            }
        if (total != size) {
            response.close()
            return null
        }
        return response
    }

    // takes all of the content, at most maxBytes (an EntityTooLargeException beyond), to where no reader
    // sees it; null when sha256 is given and the content does not have it. The caller commits or
    // discards what it gets
    fun stage(
        key: List<String>,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): S3StagedObject? {
        val digest = MessageDigest.getInstance("SHA-256")
        var upload: MultipartOutputStream? = null
        var size = 0L
        try {
            content.use {
                // what is read and not sent yet, at most one part
                var buffer = it.readNBytes(PART_BYTES)
                while (true) {
                    size += buffer.size
                    if (size > maxBytes) throw EntityTooLargeException(maxBytes)
                    digest.update(buffer)
                    val next = if (buffer.size < PART_BYTES) ByteArray(0) else it.readNBytes(PART_BYTES)
                    // all of it is one part: no multipart upload for it
                    if (next.isEmpty() && upload == null) {
                        if (sha256 != null && HexFormat.of().formatHex(digest.digest()) != sha256) return null
                        return Small(key, buffer)
                    }
                    val parts = upload ?: multipart(key).also { started -> upload = started }
                    parts.write(buffer)
                    if (next.isEmpty()) break
                    buffer = next
                }
            }
            val parts = checkNotNull(upload)
            if (sha256 != null && HexFormat.of().formatHex(digest.digest()) != sha256) {
                parts.abort()
                return null
            }
            return Large(parts, size)
        } catch (e: Exception) {
            upload?.let { runCatching { it.abort() } }
            throw e
        }
    }

    // false when there was no such object; a delete of an object that is not there succeeds at S3, so
    // it is asked first
    fun delete(key: List<String>): Boolean {
        if (size(key) == null) return false
        request(key).method(HttpMethod.DELETE).execute()
        return true
    }

    // every object below the prefix (see prefix) by its whole key with its size, a page at a time,
    // until visit says false
    fun list(
        prefix: String,
        max: Int? = pageSize,
        visit: (key: String, size: Long) -> Boolean,
    ) = pages(prefix, max = max) { page -> page.childrenWithName("Contents").all { visit(it.content("Key"), it.content("Size").toLong()) } }

    // whether there is an object below the prefix
    fun any(prefix: String): Boolean {
        var any = false
        list(prefix, max = 1) { _, _ ->
            any = true
            false
        }
        return any
    }

    // the names of the segments right below the prefix that have objects below them
    fun children(prefix: String): Set<String> {
        val names = sortedSetOf<String>()
        pages(prefix, delimiter = "/") { page ->
            page.childrenWithName("CommonPrefixes").forEach { names.add(it.content("Prefix").removePrefix(prefix).trimEnd('/')) }
            true
        }
        return names
    }

    // the bytes of the objects below the prefix
    fun bytes(prefix: String): Long {
        var bytes = 0L
        list(prefix) { _, size ->
            bytes += size
            true
        }
        return bytes
    }

    // all the objects below root, counted live by a listing
    override fun storageUsage() = StorageUsage(bytes(prefix()))

    // a part is sent where it is written, one at a time: a large content is never in memory as a whole
    private fun multipart(key: List<String>) =
        Multipart
            .s3(bucket.client)
            .bucket(bucket.name)
            .key(bucket.key(*(root + key).toTypedArray()))
            .executor(SameThread)
            .partSize(PART_BYTES)
            .partTimeout(5, TimeUnit.MINUTES)
            .outputStream()

    // a content of one part, kept until it is put in its place
    private inner class Small(
        private val key: List<String>,
        private val content: ByteArray,
    ) : S3StagedObject {
        override val size = content.size.toLong()

        override fun commit() {
            request(key).method(HttpMethod.PUT).requestBody(content).execute()
        }

        override fun discard() = Unit
    }

    // a multipart upload with all its parts sent: completed it is the object, aborted nothing is left
    private class Large(
        private val upload: MultipartOutputStream,
        override val size: Long,
    ) : S3StagedObject {
        private var committed = false

        override fun commit() {
            committed = true
            upload.close()
        }

        override fun discard() {
            if (!committed) upload.abort()
        }
    }

    // the pages of a listing (ListObjectsV2) until visit says false
    private fun pages(
        prefix: String,
        delimiter: String? = null,
        max: Int? = pageSize,
        visit: (XmlElement) -> Boolean,
    ) {
        var token: String? = null
        do {
            var request = bucket.bucketRequest().query("list-type", "2").query("prefix", prefix)
            if (delimiter != null) request = request.query("delimiter", delimiter)
            if (max != null) request = request.query("max-keys", max.toString())
            token?.let { request = request.query("continuation-token", it) }
            val page = request.responseAsXml()
            if (!visit(page)) return
            token = page.takeIf { it.content("IsTruncated") == "true" }?.content("NextContinuationToken")
        } while (token != null)
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
