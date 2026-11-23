package io.pelle.sak.storage

import io.pelle.sak.storage.model.BlobInfo
import io.pelle.sak.storage.model.BlobListEntry
import io.pelle.sak.storage.model.BlobUsage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

// a blob can not be written where a parent of its path is a blob or where it has children
class BlobPathConflictException(
    val path: BlobPath,
) : RuntimeException("'$path' conflicts with an existing blob")

// blobs at paths. A blob is written as a whole and shows at its path at once, a reader never sees half
// of one; what a write that ended without its blob left behind (e.g. the backend stopped) goes when
// the storage starts. A path has a blob or blobs below it, never both. A path that has neither is not
// there: the storage keeps no empty directory, a delete or a move takes away what it leaves empty
interface LocalStorage {
    // writes all of the stream as the blob at the path, replacing a blob there, and closes the stream.
    // A BlobPathConflictException when the path has blobs below it or a blob is on the way to it; a
    // write that fails leaves the blob that was there
    fun write(
        path: BlobPath,
        content: InputStream,
    ): BlobInfo

    // the same for what content writes; the stream it gets is the storage's to close
    fun write(
        path: BlobPath,
        content: (OutputStream) -> Unit,
    ): BlobInfo

    // the blob at the path to read, the caller closes it; null when there is none
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

    // the size and the time of the last change of the blob at the path, null when there is none
    fun stat(path: BlobPath): BlobInfo?

    // moves the blob at from to to at once, replacing a blob there; null when there is no blob at from.
    // A BlobPathConflictException as for a write
    fun move(
        from: BlobPath,
        to: BlobPath,
    ): BlobInfo?

    // removes the blob at the path, nothing when there is none. A path with blobs below it is no blob:
    // it fails, deleteTree removes those
    fun delete(path: BlobPath)

    // removes every blob below the path and the blob at it, nothing when there is none
    fun deleteTree(path: BlobPath)

    // every blob below the prefix, relative to it, sorted by its path; none when there is nothing. It
    // holds them all: forEach is for a listing too large for that
    fun list(prefix: BlobPath): List<BlobListEntry>

    // every blob below the prefix, relative to it, in no order and without keeping them: for a listing
    // too large to hold, e.g. the packs of a repository of restic
    fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    )

    // the names of the blobs and directories right below the path, sorted, without walking deeper;
    // none when there is nothing at the path. The staged writes are left out
    fun children(path: BlobPath): List<String>

    // counted without listing every blob, blobs that disappear while counting are left out
    fun usage(prefix: BlobPath): BlobUsage

    // the content of the blob at the path, null when there is none; its open() fails with a
    // NoSuchFileException when the blob went since, with a BlobChangedException when another
    // replaced it
    fun content(path: BlobPath): BlobContent?

    // the directory on disk the blobs below the path are in, for a reader that reads them there
    // itself; it must not write there
    fun directory(path: BlobPath): Path
}

// the blob was replaced since its size was taken
class BlobChangedException(
    path: BlobPath,
    expected: Long,
    actual: Long,
) : IOException("'$path' changed: $actual bytes instead of $expected")

// a blob to read: its size, and its content, opened again for every read
class BlobContent(
    val size: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}
