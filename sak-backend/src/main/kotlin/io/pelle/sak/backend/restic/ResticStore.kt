package io.pelle.sak.backend.restic

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.core.logCause
import io.pelle.sak.core.storage.StorageUsageReporter
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.LocalBlobStore
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.storage.WriteGuard
import java.io.InputStream
import java.time.Instant
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

// the files of the repositories of restic, in the storage that is the one now: the bucket of the S3
// storage while the settings of the backups say so, else the data volume (BACKUP-1). `storage` is asked
// for each call; a change of it moves no file, a repository is read where it points to. A file is
// never replaced, and a reader never sees half of one
class ResticStore(
    private val storage: () -> ResticFileStorage,
) : StorageUsageReporter {
    // on the data volume only
    constructor(storage: LocalStorage, guard: WriteGuard) : this(BlobResticFileStorage(LocalBlobStore(storage, guard)))

    constructor(storage: ResticFileStorage) : this({ storage })

    // the bytes of each repository in each storage: counted once from its files, then changed with
    // each save and delete, so a list of the repositories walks no files (BK-16). Only this store
    // writes the files, one store serves the REST protocol and the api
    private val usages = ConcurrentHashMap<Pair<String, ResourceId>, Long>()

    // a count, and a change of the files and of the count, of one repository are never at the same time
    private val locks = ConcurrentHashMap<ResourceId, Any>()

    private fun <T> locked(
        repository: ResourceId,
        block: () -> T,
    ): T = synchronized(locks.computeIfAbsent(repository) { Any() }, block)

    // adds `bytes` to the count of a repository in a storage, when it was counted
    private fun changed(
        storage: ResticFileStorage,
        repository: ResourceId,
        bytes: Long,
    ) {
        usages.computeIfPresent(storage.place to repository) { _, usage -> usage + bytes }
    }

    // the size of a file, null when there is none
    fun size(
        repository: ResourceId,
        file: ResticFile,
    ): Long? = storage().size(repository, file)

    // `length` bytes from `offset` of a file of `size`, null when there is none or it changed
    fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream? = storage().open(repository, file, offset, length, size)

    // the files of a type, by name with its size, in no order and without keeping them (BK-15)
    fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    ) {
        require(type in ResticFile.TYPES) { "no type of a repository: $type" }
        storage().forEach(repository, type, visit)
    }

    fun save(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
    ): ResticFileSaveResult {
        val storage = storage()
        if (storage.size(repository, file) != null) return ResticFileSaveResult.Exists
        if (file.type == null && storage.hasFiles(repository, DATA_TYPES)) return ResticFileSaveResult.NotEmpty
        // every file but the config is named by the sha-256 of its content
        val staged = storage.stage(repository, file, content, file.name.takeIf { file.type != null }, MAX_FILE_BYTES) ?: return ResticFileSaveResult.Damaged
        try {
            return locked(repository) {
                if (storage.size(repository, file) != null) return@locked ResticFileSaveResult.Exists
                staged.commit()
                changed(storage, repository, staged.size)
                ResticFileSaveResult.Saved
            }
        } finally {
            staged.discard()
        }
    }

    // the bytes the files of a repository take in the storage, a file being written left out;
    // the first ask after a start walks the files of the repository, see `count`
    fun usage(repository: ResourceId): Long {
        val storage = storage()
        val key = storage.place to repository
        return usages[key] ?: locked(repository) { usages.getOrPut(key) { storage.bytes(repository) } }
    }

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

    // whether anything of a repository is in the storage: what a restore of the instance left is
    // never given to a new repository (MODEL-8)
    fun exists(repository: ResourceId): Boolean = storage().exists(repository)

    // what is in the storage of repositories that are not in `known`, e.g. the ones a restore of the
    // instance left without their row; never deleted, nobody reaches them
    fun leftovers(known: Set<ResourceId>): List<String> = storage().leftovers(known)

    // all of the storage that is the one now: the repositories in the other one are not counted
    override fun storageUsage() = storage().storageUsage()

    // removes the uploads a stopped backend left in the storage that is the one now (ST-2); how many
    fun removeStale(before: Instant): Int = storage().removeStale(before)

    // false when there was no such file
    fun delete(
        repository: ResourceId,
        file: ResticFile,
    ): Boolean {
        val storage = storage()
        return locked(repository) {
            // the delete answers the size, one question to the storage (ST-7)
            val size = storage.delete(repository, file) ?: return@locked false
            changed(storage, repository, -size)
            true
        }
    }

    companion object {
        // the types of files that hold what was backed up
        private val DATA_TYPES = listOf("data", "index", "snapshots")

        // the largest file restic writes is a pack of at most 128 MiB (--pack-size), an index stays far below
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
    }
}
