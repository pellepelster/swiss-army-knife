package io.pelle.sak.storage

import io.pelle.sak.storage.model.BlobInfo
import io.pelle.sak.storage.model.BlobListEntry
import io.pelle.sak.storage.model.BlobUsage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

    // length bytes from offset, clamped to the blob; null when there is none. With size, the blob
    // opened must have it, else BlobChangedException: a reader that took the size before (e.g. for a
    // Content-Length) never gets the bytes of a blob that replaced it
    fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long? = null,
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

    // the content of the blob at the path, null when there is none; its open() fails with a
    // NoSuchFileException when the blob went since, with a BlobChangedException when another
    // replaced it
    fun content(path: BlobPath): BlobContent?
}

// the blob was replaced since its size was taken
class BlobChangedException(
    path: BlobPath,
    expected: Long,
    actual: Long,
) : IOException("'$path' changed: $actual bytes instead of $expected")

// a blob to read: its size, and its content, opened anew for every read
class BlobContent(
    val size: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}
