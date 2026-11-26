package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.internal.BlobListEntry
import io.pelle.sak.api.storage.model.internal.BlobPath
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

// the blobs of a remote storage, e.g. below a root in a bucket of the S3 storage (S3RemoteStorage). A blob
// shows whole or not at all: a small content is one put, a larger one an upload of parts that is
// completed only when all of it is there and checked. It knows nothing of what it keeps
class RemoteBlobStore(
    private val storage: RemoteStorage,
    // how many objects a listing asks for at once, what the storage gives when null
    private val pageSize: Int? = null,
) : BlobStore {
    override val place = storage.place

    override fun size(path: BlobPath) = storage.size(path)

    override fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        val end = minOf(offset + length, size)
        // nothing to read: a range of no bytes can not be asked for
        if (offset >= end) return if (storage.size(path) == size) ByteArrayInputStream(ByteArray(0)) else null
        val range = storage.openRange(path, offset, end - 1) ?: return null
        // gone, or another object took its place since its size was taken
        if (range.total != size) {
            range.content.close()
            return null
        }
        return range.content
    }

    override fun stage(
        path: BlobPath,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): StagedBlob? {
        val partBytes = storage.partBytes
        var upload: RemoteUpload? = null
        try {
            CheckedContent(content, maxBytes).use { checked ->
                // what is read and not sent yet: one part, and the next to know whether it is the last. So
                // up to two parts are in memory for each upload, besides what the storage keeps of the
                // part it sends
                var buffer = checked.readNBytes(partBytes)
                while (true) {
                    val next = if (buffer.size < partBytes) ByteArray(0) else checked.readNBytes(partBytes)
                    // all of it is one part: no upload of parts for it
                    if (next.isEmpty() && upload == null) return if (checked.matches(sha256)) Small(path, buffer) else null
                    val parts = upload ?: storage.upload(path).also { started -> upload = started }
                    parts.write(buffer)
                    if (next.isEmpty()) break
                    buffer = next
                }
                val parts = checkNotNull(upload)
                if (!checked.matches(sha256)) {
                    parts.abort()
                    return null
                }
                return Large(parts, checked.size)
            }
        } catch (e: Exception) {
            upload?.let { runCatching { it.abort() } }
            throw e
        }
    }

    // a delete of an object that is not there succeeds at S3, so its size is asked first, once
    override fun delete(path: BlobPath): Long? {
        val size = storage.size(path) ?: return null
        storage.delete(path)
        return size
    }

    override fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    ) = pages(prefix) { page ->
        page.objects.forEach(visit)
        true
    }

    override fun any(prefix: BlobPath) = storage.page(prefix, max = 1).objects.isNotEmpty()

    override fun children(prefix: BlobPath): List<String> {
        val names = sortedSetOf<String>()
        pages(prefix, children = true) { page ->
            names.addAll(page.children)
            page.objects.forEach { names.add(it.path) }
            true
        }
        return names.toList()
    }

    // the uploads of parts a stopped backend left, below the root of the store (ST-2); a content of one
    // part is never in the storage before its commit
    override fun removeStale(before: Instant) = storage.abortStale(BlobPath(emptyList()), before)

    // the pages of a listing until visit says false
    private fun pages(
        prefix: BlobPath,
        children: Boolean = false,
        visit: (RemotePage) -> Boolean,
    ) {
        var token: String? = null
        do {
            val page = storage.page(prefix, children, pageSize, token)
            if (!visit(page)) return
            token = page.next
        } while (token != null)
    }

    // a content of one part, kept until it is put in its place
    private inner class Small(
        private val path: BlobPath,
        private val content: ByteArray,
    ) : StagedBlob {
        override val size = content.size.toLong()

        override fun commit() = storage.put(path, content)

        override fun discard() = Unit
    }

    // an upload with all its parts sent: completed it is the object, aborted nothing is left. It is
    // committed only once its completion worked, so a discard after a failed one aborts it (ST-1)
    private class Large(
        private val upload: RemoteUpload,
        override val size: Long,
    ) : StagedBlob {
        private var committed = false

        override fun commit() {
            upload.complete()
            committed = true
        }

        // a failed abort does not hide the failure that led to the discard
        override fun discard() {
            if (!committed) runCatching { upload.abort() }
        }
    }
}
