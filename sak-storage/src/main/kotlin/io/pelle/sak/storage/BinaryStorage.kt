package io.pelle.sak.storage

import io.pelle.sak.storage.model.BlobInfo
import io.pelle.sak.storage.model.BlobListEntry
import io.pelle.sak.storage.model.BlobUsage
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.NoSuchFileException

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

    // moves the blob at from to to at once, replacing a blob there; null when there is no blob at from
    fun move(
        from: BlobPath,
        to: BlobPath,
    ): BlobInfo?

    fun delete(path: BlobPath)

    fun deleteTree(path: BlobPath)

    fun list(prefix: BlobPath): List<BlobListEntry>

    // counted without listing every blob, blobs that disappear while counting are left out
    fun usage(prefix: BlobPath): BlobUsage
}

// a blob to read: its size, and its content, opened anew for every read
class BlobContent(
    val size: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

// the content of the blob at the path, null when there is none; a blob deleted after this fails
// open() with a NoSuchFileException
fun BinaryStorage.content(path: BlobPath): BlobContent? =
    stat(path)?.let { info -> BlobContent(info.size) { open(path) ?: throw NoSuchFileException(path.toString()) } }
