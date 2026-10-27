package io.pelle.sak.s3.storage

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

data class BlobInfo(
    val size: Long,
    val lastModified: Instant,
)

data class BlobListEntry(
    val segments: List<String>,
    val size: Long,
    val lastModified: Instant,
) {
    val path get() = segments.joinToString("/")
}

// how many blobs are below a path and how large they are altogether
data class BlobUsage(
    val blobs: Long,
    val bytes: Long,
)

// a blob can not be written where a parent of its path is a blob or where it has children
class BlobPathConflictException(
    val path: BlobPath,
) : RuntimeException("'$path' conflicts with an existing blob")

interface BinaryStorage {
    fun write(
        path: BlobPath,
        content: InputStream,
    ): BlobInfo

    fun write(
        path: BlobPath,
        content: (OutputStream) -> Unit,
    ): BlobInfo

    fun open(path: BlobPath): InputStream?

    fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
    ): InputStream?

    fun stat(path: BlobPath): BlobInfo?

    fun delete(path: BlobPath)

    fun deleteTree(path: BlobPath)

    fun list(prefix: BlobPath): List<BlobListEntry>

    // counted without listing every blob, blobs that disappear while counting are left out
    fun usage(prefix: BlobPath): BlobUsage
}
