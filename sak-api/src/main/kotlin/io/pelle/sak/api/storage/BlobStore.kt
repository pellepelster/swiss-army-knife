package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.BlobListEntry
import io.pelle.sak.api.storage.model.BlobPath
import java.io.InputStream
import java.time.Instant

// blobs at paths that show whole or not at all, a staged one nowhere before its commit. A blob is never
// at a path that has blobs below it or is below a blob: the caller keeps it so, what a store does
// otherwise is not defined. Wherever they are kept: on the data volume
// (LocalBlobStore on a LocalStorage) or in the bucket of the S3 storage (RemoteBlobStore on a RemoteStorage, S3RemoteStorage). It knows nothing of what it keeps;
// its total is what it reports (StorageUsageReporter)
interface BlobStore : StorageUsageReporter {
    // where the blobs are, e.g. "volume"
    val place: String

    // the size of the blob, null when there is none
    fun size(path: BlobPath): Long?

    // `length` bytes from `offset` of the blob of `size`, clamped to it, the caller closes them; null
    // when there is none, or another blob took its place since its size was taken. An
    // IllegalArgumentException for a negative offset or length
    fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream?

    // takes all of the content, at most maxBytes (an EntityTooLargeException beyond), to where no reader
    // sees it; null when sha256 is given and the content does not have it. The caller commits or
    // discards what it gets
    fun stage(
        path: BlobPath,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): StagedBlob?

    // removes the blob, and answers its size, so a caller that counts needs not ask for it before
    // (ST-7); null when there was none
    fun delete(path: BlobPath): Long?

    // every blob below the prefix, its path relative to it, in no order and without keeping them; a blob
    // at the prefix itself is not below it
    fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    )

    // whether there is a blob below the prefix
    fun any(prefix: BlobPath): Boolean

    // the names right below the prefix that have blobs below them or are blobs, sorted
    fun children(prefix: BlobPath): List<String>

    // the bytes of the blobs below the prefix
    fun bytes(prefix: BlobPath): Long {
        var bytes = 0L
        forEach(prefix) { bytes += it.size }
        return bytes
    }

    // all of the store, counted live on each call: on a remote storage a listing of every object, so it
    // is for a look now and then (ST-10); a staged blob is not counted
    override fun storageUsage(): StorageUsage {
        var bytes = 0L
        var items = 0L
        forEach(BlobPath(emptyList())) {
            bytes += it.size
            items++
        }
        return StorageUsage(bytes, items, Instant.now())
    }

    // removes what was staged before `before` and never committed nor discarded, e.g. by a backend that
    // stopped during an upload; how many
    fun removeStale(before: Instant): Int
}

// a content on its way to its blob: committed it is the blob, discarded nothing of it is left
interface StagedBlob {
    val size: Long

    // puts the blob in its place, whole at once
    fun commit()

    // removes what was staged; nothing after a commit
    fun discard()
}
