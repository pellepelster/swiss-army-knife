package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.BlobListEntry
import io.pelle.sak.api.storage.model.BlobPath
import java.io.InputStream
import java.time.Instant

// objects at paths in a storage elsewhere, e.g. the bucket of an S3 storage: each written whole by one
// request or by an upload of parts, read in ranges, listed a page at a time. Unlike LocalStorage it
// moves nothing and has no directories: a path is there as long as an object is below it. An answer
// the storage should not give is an IOException, a request that fails one of the client, unchecked
interface RemoteStorage {
    // where the objects are, e.g. for the log
    val place: String

    // the least size of a part of an upload, but for its last
    val partBytes: Int

    // the size of the object, null when there is none
    fun size(path: BlobPath): Long?

    // the bytes `first` to `last` of the object; null when there is none, or none of the range
    fun openRange(
        path: BlobPath,
        first: Long,
        last: Long,
    ): RemoteRange?

    // writes the content as the object at the path, replacing one there
    fun put(
        path: BlobPath,
        content: ByteArray,
    )

    // starts an upload of parts of the object at the path; it shows once completed
    fun upload(path: BlobPath): RemoteUpload

    // removes the object at the path, nothing when there is none
    fun delete(path: BlobPath)

    // aborts the uploads of parts below the prefix that started before `before` and were neither
    // completed nor aborted; how many
    fun abortStale(
        prefix: BlobPath,
        before: Instant,
    ): Int

    // a page of the objects below the prefix, each relative to it; with children, the objects right
    // below it and the names of what is deeper instead
    fun page(
        prefix: BlobPath,
        children: Boolean = false,
        max: Int? = null,
        token: String? = null,
    ): RemotePage
}

// a range of an object, its caller closes it; the size of the whole object, as far as the storage tells it
class RemoteRange(
    val content: InputStream,
    val total: Long?,
)

// an upload of parts, a part each write; completed it is the object, aborted nothing of it is left
interface RemoteUpload {
    fun write(part: ByteArray)

    fun complete()

    fun abort()
}

// a page of a listing: the objects, the names of what is deeper (with children), and the token of the
// next page, null on the last
class RemotePage(
    val objects: List<BlobListEntry>,
    val children: List<String>,
    val next: String?,
)
