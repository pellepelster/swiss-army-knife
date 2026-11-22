package io.pelle.sak.backend.restic

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.core.logCause
import io.pelle.sak.backend.isFree
import io.pelle.sak.backend.blobPath
import io.pelle.sak.backend.leftovers
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobPath
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


private val logger = KotlinLogging.logger {}

// what saving a file of a repository came to
sealed interface ResticFileSaveResult {
    data object Saved : ResticFileSaveResult

    // a file of the name is there already: restic never writes one twice, a file is not replaced
    data object Exists : ResticFileSaveResult

    // the content is not the one its name is the sha-256 of: an upload that broke on its way
    data object Damaged : ResticFileSaveResult

    // a config for a repository that has backed up data: it was initialized, its config is missing, and
    // a new one would hide what is there (BK-6). Keys and locks alone are no data: restic init writes
    // its key before the config
    data object NotEmpty : ResticFileSaveResult
}

// a file of a repository: the config, or one of a type by its name
data class ResticFile(
    val type: String?,
    val name: String,
) {
    init {
        require(if (type == null) name == CONFIG else type in TYPES && NAME.matches(name)) { "no file of a repository: $type/$name" }
    }

    companion object {
        const val CONFIG = "config"

        // the kinds of files of a repository of restic, a directory each
        val TYPES = setOf("data", "keys", "locks", "snapshots", "index")

        // every file but the config is named by the sha-256 of its content
        val NAME = Regex("^[0-9a-f]{64}$")

        val config = ResticFile(null, CONFIG)
    }
}

// the files of the repositories of restic on the data volume, below restic/<resource id> (BACKUP-1,
// MODEL-8); written
// through `storage` (MODEL-7), each only while the volume has room. A file is written beside its
// place and moved there once its content is checked, so a reader never sees half of it
class ResticStore(
    private val storage: BinaryStorage,
    private val guard: WriteGuard,
) {
    // the bytes of each repository: counted once from its files, then changed with each save and
    // delete, so a list of the repositories walks no files (BK-16). Only this store writes the files,
    // one store serves the REST protocol and the api
    private val usages = ConcurrentHashMap<ResourceId, Long>()

    // a count, and a change of the files and of the count, of one repository are never at the same time
    private val locks = ConcurrentHashMap<ResourceId, Any>()

    private fun <T> locked(
        repository: ResourceId,
        block: () -> T,
    ): T = synchronized(locks.computeIfAbsent(repository) { Any() }, block)

    // adds `bytes` to the count of a repository, when it was counted
    private fun changed(
        repository: ResourceId,
        bytes: Long,
    ) {
        usages.computeIfPresent(repository) { _, usage -> usage + bytes }
    }

    private fun path(
        repository: ResourceId,
        file: ResticFile,
    ) = if (file.type == null) repository.blobPath(file.name) else repository.blobPath(file.type, file.name)

    // the size of a file, null when there is none
    fun size(
        repository: ResourceId,
        file: ResticFile,
    ): Long? = storage.stat(path(repository, file))?.size

    // `length` bytes from `offset` of a file of `size`, null when there is none or it changed
    fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? = storage.openRange(path(repository, file), offset, length, size)

    // the files of a type, by name with its size, in no order and without keeping them (BK-15)
    fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) {
        require(type in ResticFile.TYPES) { "no type of a repository: $type" }
        storage.forEach(repository.blobPath(type)) {
            if (it.segments.size == 1 && ResticFile.NAME.matches(it.segments.single())) visit(it.segments.single(), it.size)
        }
    }

    fun save(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
    ): ResticFileSaveResult {
        val target = path(repository, file)
        if (storage.stat(target) != null) return ResticFileSaveResult.Exists
        if (file.type == null && hasData(repository)) return ResticFileSaveResult.NotEmpty
        guard.requireRoom()
        val staged = repository.blobPath(STAGING, UUID.randomUUID().toString())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            storage.write(staged, DigestInputStream(guard.guarded(content, minOf(guard.maxBytes, MAX_FILE_BYTES)), digest))
            if (file.type != null && HexFormat.of().formatHex(digest.digest()) != file.name) return ResticFileSaveResult.Damaged
            return locked(repository) {
                if (storage.stat(target) != null) return@locked ResticFileSaveResult.Exists
                storage.move(staged, target)
                changed(repository, storage.stat(target)?.size ?: 0)
                ResticFileSaveResult.Saved
            }
        } finally {
            storage.delete(staged)
        }
    }

    // the bytes the files of a repository take on the data volume, a file being written left out;
    // the first ask after a start walks the files of the repository, see `count`
    fun usage(repository: ResourceId): Long = usages[repository] ?: locked(repository) { usages.getOrPut(repository) { files(repository) } }

    // counts the repositories, so the first list of them does not wait: run in the background at the
    // start. One that can not be counted is logged and counted with its first ask, the others follow
    fun count(repositories: Collection<ResourceId>) {
        repositories.forEach { repository ->
            try {
                usage(repository)
            } catch (e: Exception) {
                logger.warn(logCause(e)) { "could not count the repository of restic $repository" }
            }
        }
    }

    // the bytes of the config and of the files of every type, the staged ones left out
    private fun files(repository: ResourceId) =
        (storage.stat(path(repository, ResticFile.config))?.size ?: 0) +
            ResticFile.TYPES.sumOf { storage.usage(repository.blobPath(it)).bytes }

    // whether a repository has backed up anything: data, an index or a snapshot
    private fun hasData(repository: ResourceId) = DATA_TYPES.any { storage.children(repository.blobPath(it)).isNotEmpty() }

    // whether anything of a repository is on the data volume: a directory left from a restore of the
    // instance is never given to a new repository (MODEL-8)
    fun exists(repository: ResourceId): Boolean = !storage.isFree(repository)

    // the directories of repositories that are not in `known`, e.g. the ones a restore of the instance
    // left without their row; never deleted, nobody reaches them
    fun leftovers(known: Set<ResourceId>): List<String> = storage.leftovers(known)

    // false when there was no such file
    fun delete(
        repository: ResourceId,
        file: ResticFile,
    ): Boolean {
        val path = path(repository, file)
        return locked(repository) {
            val size = storage.stat(path)?.size ?: return@locked false
            storage.delete(path)
            changed(repository, -size)
            true
        }
    }

    companion object {
        // where a file is written before it is checked; no type of restic is named so
        private const val STAGING = "staging"

        // the types of files that hold what was backed up
        private val DATA_TYPES = listOf("data", "index", "snapshots")

        // the largest file restic writes is a pack of at most 128 MiB (--pack-size), an index stays far below
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
    }
}
