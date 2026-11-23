package io.pelle.sak.backend.restic

import io.pelle.sak.backend.blobPath
import io.pelle.sak.backend.isFree
import io.pelle.sak.backend.leftovers
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.WriteGuard
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// the files of the repositories on the data volume, below restic/<resource id> (BACKUP-1, MODEL-8);
// written through `storage` (MODEL-7), each only while the volume has room. A file is written beside
// its place and moved there, so a reader never sees half of it
class BinaryResticFileStorage(
    private val storage: BinaryStorage,
    private val guard: WriteGuard,
) : ResticFileStorage {
    override val place = "volume"

    private fun path(
        repository: ResourceId,
        file: ResticFile,
    ) = if (file.type == null) repository.blobPath(file.name) else repository.blobPath(file.type, file.name)

    override fun size(
        repository: ResourceId,
        file: ResticFile,
    ): Long? = storage.stat(path(repository, file))?.size

    override fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? = storage.openRange(path(repository, file), offset, length, size)

    override fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) {
        storage.forEach(repository.blobPath(type)) {
            if (it.segments.size == 1 && ResticFile.NAME.matches(it.segments.single())) visit(it.segments.single(), it.size)
        }
    }

    override fun stage(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): ResticStagedFile? {
        guard.requireRoom()
        val target = path(repository, file)
        val staged = repository.blobPath(STAGING, UUID.randomUUID().toString())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val info = storage.write(staged, DigestInputStream(guard.guarded(content, minOf(guard.maxBytes, maxBytes)), digest))
            if (sha256 != null && HexFormat.of().formatHex(digest.digest()) != sha256) {
                storage.delete(staged)
                return null
            }
            return object : ResticStagedFile {
                override val size = info.size

                override fun commit() {
                    storage.move(staged, target)
                }

                override fun discard() = storage.delete(staged)
            }
        } catch (e: Exception) {
            storage.delete(staged)
            throw e
        }
    }

    override fun delete(
        repository: ResourceId,
        file: ResticFile,
    ): Boolean {
        val path = path(repository, file)
        if (storage.stat(path) == null) return false
        storage.delete(path)
        return true
    }

    override fun hasFiles(
        repository: ResourceId,
        types: Collection<String>,
    ) = types.any { storage.children(repository.blobPath(it)).isNotEmpty() }

    override fun bytes(repository: ResourceId) =
        (storage.stat(path(repository, ResticFile.config))?.size ?: 0) +
            ResticFile.TYPES.sumOf { storage.usage(repository.blobPath(it)).bytes }

    override fun exists(repository: ResourceId): Boolean = !storage.isFree(repository)

    override fun leftovers(known: Set<ResourceId>): List<String> = storage.leftovers(known)

    private companion object {
        // where a file is written before it is checked; no type of restic is named so
        const val STAGING = "staging"
    }
}
