package io.pelle.sak.backend.restic

import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3StagedObject
import io.pelle.sak.storage.s3.S3Storage
import java.io.InputStream

// the files of the repositories in the bucket of the S3 storage, where they are on the data volume of
// the deployment: <deployment>/backend/data/restic/<resource id>/config and
// <deployment>/backend/data/restic/<resource id>/<type>/<name> (BACKUP-1, MODEL-8). The objects
// themselves, whole or not at all, are S3Storage's; what restic backs up is encrypted before it gets
// here (BACKUP-3)
class S3ResticFileStorage(
    bucket: S3Bucket,
    // how many objects a listing asks for at once, what the storage gives when null
    pageSize: Int? = null,
) : ResticFileStorage {
    private val storage = S3Storage(bucket, ROOT, pageSize)

    override val place = storage.place

    private fun key(
        repository: ResourceId,
        file: ResticFile,
    ) = listOfNotNull(repository.value, file.type, file.name)

    override fun size(
        repository: ResourceId,
        file: ResticFile,
    ) = storage.size(key(repository, file))

    override fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ) = storage.openRange(key(repository, file), offset, length, size)

    override fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) = storage.list(storage.prefix(repository.value, type)) { key, size ->
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
    ): ResticStagedFile? = storage.stage(key(repository, file), content, sha256, maxBytes)?.let(::Staged)

    // the object on its way, as the file restic waits for
    private class Staged(
        private val staged: S3StagedObject,
    ) : ResticStagedFile {
        override val size get() = staged.size

        override fun commit() = staged.commit()

        override fun discard() = staged.discard()
    }

    override fun delete(
        repository: ResourceId,
        file: ResticFile,
    ) = storage.delete(key(repository, file))

    override fun hasFiles(
        repository: ResourceId,
        types: Collection<String>,
    ) = types.any { storage.any(storage.prefix(repository.value, it)) }

    // the config and the files of the types restic knows, nothing else below the repository
    override fun bytes(repository: ResourceId): Long {
        val prefix = storage.prefix(repository.value)
        var bytes = 0L
        storage.list(prefix) { key, size ->
            val below = key.removePrefix(prefix).split('/')
            if (below == listOf(ResticFile.CONFIG) || (below.size == 2 && below[0] in ResticFile.TYPES)) bytes += size
            true
        }
        return bytes
    }

    override fun exists(repository: ResourceId) = storage.any(storage.prefix(repository.value))

    override fun leftovers(known: Set<ResourceId>): List<String> =
        storage.children(storage.prefix()).filter { name -> ResourceId.of(name)?.let { it !in known } == true }

    private companion object {
        // below the deployment in the bucket, as the directory of the repositories is on the data volume
        val ROOT = listOf("backend", "data", "restic")
    }
}
