package io.pelle.sak.storage

import io.pelle.sak.api.storage.BlobStore
import io.pelle.sak.api.storage.CheckedContent
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.api.storage.StagedBlob
import io.pelle.sak.api.storage.model.internal.BlobChangedException
import io.pelle.sak.api.storage.model.internal.BlobListEntry
import io.pelle.sak.api.storage.model.internal.BlobPath
import java.io.InputStream
import java.time.Instant

// the blobs of a local storage on the data volume (MODEL-7), each written only while the volume has
// room (guard). A blob is staged by the storage, where no reader sees it, and moved to its place at
// once on its commit
class LocalBlobStore(
    private val storage: LocalStorage,
    private val guard: WriteGuard,
    // where the blobs are, e.g. for the log and the counts of ResticStore: one name for each store
    override val place: String = "volume",
) : BlobStore {

    override fun size(path: BlobPath) = storage.stat(path)?.size

    // another blob in its place, or none any more, is null as on every store
    override fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? =
        try {
            storage.openRange(path, offset, length, size)
        } catch (_: BlobChangedException) {
            null
        }

    override fun stage(
        path: BlobPath,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): StagedBlob? {
        guard.requireRoom()
        val checked = CheckedContent(guard.guarded(content), maxBytes)
        // where no reader sees it, in the staging of the storage (ST-8)
        val staged = storage.stage(checked)
        if (!checked.matches(sha256)) {
            staged.discard()
            return null
        }
        return object : StagedBlob {
            override val size = staged.info.size

            override fun commit() {
                staged.commit(path)
            }

            override fun discard() = staged.discard()
        }
    }

    // the staged writes a stopped backend left (ST-2)
    override fun removeStale(before: Instant) = storage.removeStale(before)

    // the size of a stat before, the answer of the delete itself: a delete of another meanwhile is none
    override fun delete(path: BlobPath): Long? {
        val size = storage.stat(path)?.size ?: return null
        return size.takeIf { storage.delete(path) }
    }

    override fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    ) = storage.forEach(prefix, visit)

    override fun any(prefix: BlobPath) = storage.children(prefix).isNotEmpty()

    override fun children(prefix: BlobPath) = storage.children(prefix)

}
