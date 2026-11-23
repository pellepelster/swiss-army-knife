package io.pelle.sak.backend.restic

import com.github.davidmoten.aws.lw.client.HttpMethod
import com.github.davidmoten.aws.lw.client.Multipart
import com.github.davidmoten.aws.lw.client.MultipartOutputStream
import io.pelle.sak.backend.storage.S3Bucket
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.EntityTooLargeException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

// the files of the repositories in the bucket of the S3 storage, where they are on the data volume of
// the deployment: <deployment>/backend/data/restic/<resource id>/config and
// <deployment>/backend/data/restic/<resource id>/<type>/<name> (BACKUP-1, MODEL-8). An object shows whole or not at all: a
// small file is one PUT, a larger one a multipart upload that is completed only when all of it is
// there and checked. What restic backs up is encrypted before it gets here (BACKUP-3)
class S3ResticFileStorage(
    private val bucket: S3Bucket,
    // how many objects a listing asks for at once, what the storage gives when null
    private val pageSize: Int? = null,
) : ResticFileStorage {
    override val place = "s3:${bucket.endpoint.url}/${bucket.name}/${bucket.prefix}"

    // the key all repositories of the deployment are below
    private val root = bucket.key(*ROOT.toTypedArray())

    private fun key(
        repository: ResourceId,
        file: ResticFile,
    ) = ROOT + listOfNotNull(repository.value, file.type, file.name)

    // a request of the object of a file
    private fun request(
        repository: ResourceId,
        file: ResticFile,
    ) = bucket.request(*key(repository, file).toTypedArray())

    override fun size(
        repository: ResourceId,
        file: ResticFile,
    ): Long? {
        val response = request(repository, file).method(HttpMethod.HEAD).response()
        return when (response.statusCode()) {
            200 -> response.firstHeader("Content-Length").map { it.toLong() }.orElseThrow { IOException("the storage answered no size") }
            404 -> null
            else -> throw IOException("the storage answered ${response.statusCode()} for the size of a file")
        }
    }

    override fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? {
        val end = minOf(offset + length, size)
        // nothing to read: a range of no bytes can not be asked for
        if (offset >= end) return if (size(repository, file) == size) ByteArrayInputStream(ByteArray(0)) else null
        val response = request(repository, file).header("Range", "bytes=$offset-${end - 1}").responseInputStream()
        val total =
            when (response.statusCode()) {
                206 -> response.header("Content-Range").map { it.substringAfterLast('/').toLongOrNull() }.orElse(null)
                // all of it, where the storage answers a range of all so
                200 -> response.header("Content-Length").map { it.toLongOrNull() }.orElse(null)?.takeIf { offset == 0L }
                404, 416 -> null
                else -> {
                    val status = response.statusCode()
                    response.close()
                    throw IOException("the storage answered $status for a file")
                }
            }
        // gone, or another file took its place since its size was taken
        if (total != size) {
            response.close()
            return null
        }
        return response
    }

    override fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) = list("$root/${repository.value}/$type/") { key, size ->
        val name = key.substringAfterLast('/')
        if (ResticFile.NAME.matches(name)) visit(name, size)
        true
    }

    override fun stage(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): ResticStagedFile? {
        val key = key(repository, file)
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

    // a part is sent where it is written, one at a time: a pack of restic is never in memory as a whole
    private fun multipart(key: List<String>) =
        Multipart
            .s3(bucket.client)
            .bucket(bucket.name)
            .key(bucket.key(*key.toTypedArray()))
            .executor(SameThread)
            .partSize(PART_BYTES)
            .partTimeout(5, TimeUnit.MINUTES)
            .outputStream()

    // a file of one part, kept until it is put in its place
    private inner class Small(
        private val key: List<String>,
        private val content: ByteArray,
    ) : ResticStagedFile {
        override val size = content.size.toLong()

        override fun commit() {
            bucket.request(*key.toTypedArray()).method(HttpMethod.PUT).requestBody(content).execute()
        }

        override fun discard() = Unit
    }

    // a multipart upload with all its parts sent: completed it is the file, aborted nothing is left
    private class Large(
        private val upload: MultipartOutputStream,
        override val size: Long,
    ) : ResticStagedFile {
        private var committed = false

        override fun commit() {
            committed = true
            upload.close()
        }

        override fun discard() {
            if (!committed) upload.abort()
        }
    }

    override fun delete(
        repository: ResourceId,
        file: ResticFile,
    ): Boolean {
        // a delete of an object that is not there succeeds, restic is told that there was none
        if (size(repository, file) == null) return false
        request(repository, file).method(HttpMethod.DELETE).execute()
        return true
    }

    override fun hasFiles(
        repository: ResourceId,
        types: Collection<String>,
    ) = types.any { any("$root/${repository.value}/$it/") }

    override fun bytes(repository: ResourceId): Long {
        var bytes = 0L
        list("$root/${repository.value}/") { key, size ->
            val below = key.removePrefix("$root/${repository.value}/").split('/')
            if (below == listOf(ResticFile.CONFIG) || (below.size == 2 && below[0] in ResticFile.TYPES)) bytes += size
            true
        }
        return bytes
    }

    override fun exists(repository: ResourceId) = any("$root/${repository.value}/")

    override fun leftovers(known: Set<ResourceId>): List<String> {
        val names = sortedSetOf<String>()
        pages("$root/", delimiter = "/") { page ->
            page.childrenWithName("CommonPrefixes").forEach { names.add(it.content("Prefix").removePrefix("$root/").trimEnd('/')) }
            true
        }
        return names.filter { name -> ResourceId.of(name)?.let { it !in known } == true }
    }

    private fun any(prefix: String): Boolean {
        var any = false
        list(prefix, max = 1) { _, _ ->
            any = true
            false
        }
        return any
    }

    // every object below the prefix with its size, a page at a time, until visit says false
    private fun list(
        prefix: String,
        max: Int? = pageSize,
        visit: (key: String, size: Long) -> Boolean,
    ) = pages(prefix, max = max) { page -> page.childrenWithName("Contents").all { visit(it.content("Key"), it.content("Size").toLong()) } }

    // the pages of a listing (ListObjectsV2) until visit says false
    private fun pages(
        prefix: String,
        delimiter: String? = null,
        max: Int? = pageSize,
        visit: (com.github.davidmoten.aws.lw.client.xml.XmlElement) -> Boolean,
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

    private companion object {
        // below the deployment in the bucket, as the directory of the repositories is on the data volume
        val ROOT = listOf("backend", "data", "restic")

        // the size of a part of a multipart upload, the least S3 takes
        const val PART_BYTES = 5 * 1024 * 1024
    }
}
