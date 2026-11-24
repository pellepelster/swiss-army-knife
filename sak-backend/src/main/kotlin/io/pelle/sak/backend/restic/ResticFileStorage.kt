package io.pelle.sak.backend.restic

import io.pelle.sak.api.storage.StorageUsageReporter
import io.pelle.sak.model.ResourceId
import java.io.InputStream
import java.time.Instant

// the files of the repositories of restic, wherever they are kept: on the data volume
// or in the bucket of the S3 storage, both as BlobResticFileStorage on their BlobStore. ResticStore
// keeps what does not depend on the place: a file is never replaced, and the bytes of a repository
interface ResticFileStorage : StorageUsageReporter {
    // which storage it is, e.g. "volume": the bytes of a repository are counted for each
    val place: String

    // the size of a file, null when there is none
    fun size(
        repository: ResourceId,
        file: ResticFile,
    ): Long?

    // `length` bytes from `offset` of a file of `size`, the caller closes them; null when there is no
    // file or it has another size
    fun open(
        repository: ResourceId,
        file: ResticFile,
        offset: Long,
        length: Long,
        size: Long,
    ): InputStream?

    // the files of a type, by name with its size, in no order and without keeping them
    fun forEach(
        repository: ResourceId,
        type: String,
        visit: (name: String, size: Long) -> Unit,
    )

    // takes all of the content, at most maxBytes (an EntityTooLargeException beyond), to where no
    // reader sees it; null when sha256 is given and the content does not have it. The caller commits
    // or discards what it gets
    fun stage(
        repository: ResourceId,
        file: ResticFile,
        content: InputStream,
        sha256: String?,
        maxBytes: Long,
    ): ResticStagedFile?

    // the size of the file it removed, null when there was none
    fun delete(
        repository: ResourceId,
        file: ResticFile,
    ): Long?

    // whether the repository has a file of one of the types
    fun hasFiles(
        repository: ResourceId,
        types: Collection<String>,
    ): Boolean

    // the bytes of the config and of the files of every type, a staged file left out
    fun bytes(repository: ResourceId): Long

    // whether anything of the repository is there, also what a restore of the instance left (MODEL-8)
    fun exists(repository: ResourceId): Boolean

    // what is there under a resource id that is not in `known`; never deleted
    fun leftovers(known: Set<ResourceId>): List<String>

    // removes the uploads a stopped backend left, started before `before` (ST-2); how many
    fun removeStale(before: Instant): Int
}

// a file that is taken and not yet in its place
interface ResticStagedFile {
    val size: Long

    // puts the file in its place, whole at once
    fun commit()

    // removes what was staged; nothing after a commit
    fun discard()
}
