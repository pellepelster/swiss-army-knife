package io.pelle.sak.backend.restic

import io.pelle.sak.backend.blobPath
import io.pelle.sak.backend.leftovers
import io.pelle.sak.model.ResourceId
import io.pelle.sak.api.storage.BlobStore
import io.pelle.sak.api.storage.StagedBlob
import io.pelle.sak.api.storage.model.BlobPath
import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.s3BlobStore
import java.io.InputStream
import java.time.Instant

// the files of the repositories in a store of blobs, the same way on the data volume and in the bucket
// of the S3 storage: <resource id>/config and <resource id>/<type>/<name> (BACKUP-1, MODEL-8). The
// blobs themselves, whole or not at all, are the store's; what restic backs up is encrypted before it
// gets here (BACKUP-3)
class BlobResticFileStorage(
    private val store: BlobStore,
) : ResticFileStorage {
    override val place = store.place

    private fun path(
        repository: ResourceId,
        file: ResticFile,
    ) = repository.blobPath(*listOfNotNull(file.type, file.name).toTypedArray())

    override fun size(
        repository: ResourceId,
        file: ResticFile,
    ) = store.size(path(repository, file))

    override fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ) = store.openRange(path(repository, file), offset, length, size)

    override fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) = store.forEach(repository.blobPath(type)) {
        if (it.segments.size == 1 && ResticFile.NAME.matches(it.segments.single())) visit(it.segments.single(), it.size)
    }

    override fun stage(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): ResticStagedFile? = store.stage(path(repository, file), content, sha256, maxBytes)?.let(::Staged)

    // the blob on its way, as the file restic waits for
    private class Staged(
        private val staged: StagedBlob,
    ) : ResticStagedFile {
        override val size get() = staged.size

        override fun commit() = staged.commit()

        override fun discard() = staged.discard()
    }

    override fun delete(
        repository: ResourceId,
        file: ResticFile,
    ) = store.delete(path(repository, file))

    override fun hasFiles(
        repository: ResourceId,
        types: Collection<String>,
    ) = types.any { store.any(repository.blobPath(it)) }

    // the config and the files of the types restic knows, nothing else below the repository
    override fun bytes(repository: ResourceId): Long {
        var bytes = 0L
        store.forEach(repository.blobPath()) {
            if (it.segments == listOf(ResticFile.CONFIG) || (it.segments.size == 2 && it.segments[0] in ResticFile.TYPES)) bytes += it.size
        }
        return bytes
    }

    override fun exists(repository: ResourceId) = store.any(repository.blobPath())

    // the names right below the root that are a resource id no row has, e.g. left by a restore of the
    // instance (MODEL-8)
    override fun leftovers(known: Set<ResourceId>): List<String> =
        leftovers(store.children(BlobPath(emptyList())), known)

    override fun removeStale(before: Instant) = store.removeStale(before)

    // all of the store, the files of restic and what else is there
    override fun storageUsage() = store.storageUsage()

    companion object {
        // below the deployment in the bucket, as the directory of the repositories is on the data volume
        private val S3_ROOT = BlobPath(listOf("backend", "data", "restic"))

        // the repositories in the bucket of the S3 storage
        fun s3(
            bucket: S3Bucket,
            // how many objects a listing asks for at once, what the storage gives when null
            pageSize: Int? = null,
        ) = BlobResticFileStorage(s3BlobStore(bucket, S3_ROOT, pageSize))
    }
}
