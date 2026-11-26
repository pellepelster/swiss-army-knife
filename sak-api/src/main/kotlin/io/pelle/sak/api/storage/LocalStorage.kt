package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.internal.BlobPathConflictException
import io.pelle.sak.api.storage.model.internal.BlobChangedException
import io.pelle.sak.api.storage.model.internal.BlobContent
import io.pelle.sak.api.storage.model.internal.BlobInfo
import io.pelle.sak.api.storage.model.internal.BlobListEntry
import io.pelle.sak.api.storage.model.internal.BlobPath
import io.pelle.sak.api.storage.model.internal.BlobUsage
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant


// blobs at paths. A blob is written as a whole and shows at its path at once, a reader never sees half
// of one; what a write that ended without its blob left behind (e.g. the backend stopped) goes when
// the storage starts. A path has a blob or blobs below it, never both. A path that has neither is not
// there: the storage keeps no empty directory, a delete or a move takes away what it leaves empty
interface LocalStorage : StorageUsageReporter {
    // writes all of the stream as the blob at the path, replacing a blob there, and closes the stream.
    // A BlobPathConflictException when the path has blobs below it or a blob is on the way to it; a
    // write that fails leaves the blob that was there
    fun write(
        path: BlobPath,
        content: InputStream,
    ): BlobInfo = write(path) { output -> content.use { it.copyTo(output) } }

    // the same for what content writes; the stream it gets is the storage's to close
    fun write(
        path: BlobPath,
        content: (OutputStream) -> Unit,
    ): BlobInfo

    // writes all of the stream to where no reader sees it, and closes the stream; the caller commits it
    // to its path or discards it. What is never committed goes with removeStale, or when the storage
    // starts
    fun stage(content: InputStream): StagedWrite

    // removes what was staged before `before` and never committed or discarded, e.g. by a write the
    // backend stopped in; how many
    fun removeStale(before: Instant): Int

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
    // it fails, deleteTree removes those. Whether there was one
    fun delete(path: BlobPath): Boolean

    // removes every blob below the path and the blob at it, nothing when there is none
    fun deleteTree(path: BlobPath)

    // every blob below the prefix, relative to it, sorted by its path; none when there is nothing. It
    // holds them all: forEach is for a listing too large for that
    fun list(prefix: BlobPath): List<BlobListEntry> {
        val entries = mutableListOf<BlobListEntry>()
        forEach(prefix) { entries.add(it) }
        return entries.sortedBy { it.path }
    }

    // every blob below the prefix, relative to it, in no order and without keeping them: for a listing
    // too large to hold, e.g. the packs of a repository of restic
    fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    )

    // the blobs and the directories below root/below, in the order of their keys as strings (the order of
    // S3, the key being the path from root joined with slashes), one directory read at a time, so a walk
    // that stops early reads only what it passed. Nothing up to startAfter is met: neither a blob whose
    // key is not after it, nor a directory whose keys all are not; nor anything whose key starts with
    // skipUnder. visit says how to go on
    fun walkInKeyOrder(
        root: BlobPath,
        below: List<String>,
        startAfter: String?,
        skipUnder: String?,
        visit: (KeyOrderEntry) -> Walk,
    )

    // the names of the blobs and directories right below the path, sorted, without walking deeper;
    // none when there is nothing at the path. The staged writes are left out
    fun children(path: BlobPath): List<String>

    // counted without listing every blob, blobs that disappear while counting are left out
    fun usage(prefix: BlobPath): BlobUsage {
        var blobs = 0L
        var bytes = 0L
        forEach(prefix) {
            blobs++
            bytes += it.size
        }
        return BlobUsage(blobs, bytes)
    }

    // the content of the blob at the path, null when there is none; its open() fails with a
    // NoSuchFileException when the blob went since, with a BlobChangedException when another
    // replaced it
    fun content(path: BlobPath): BlobContent? {
        val info = stat(path) ?: return null
        return BlobContent(info.size) {
            openRange(path, 0, info.size, info.size) ?: throw NoSuchFileException(path.toString())
        }
    }

    // all the blobs of the storage, counted now: its bytes and its files; a staged write is not counted
    override fun storageUsage(): StorageUsage {
        val usage = usage(BlobPath(emptyList()))
        return StorageUsage(usage.bytes, usage.blobs, Instant.now())
    }

    // the directory on disk the blobs below the path are in, for a reader that reads them there
    // itself; it must not write there
    fun directory(path: BlobPath): Path
}

// a content written where no reader sees it, until it is committed to its path at once
interface StagedWrite {
    val info: BlobInfo

    // moves it to the path, replacing a blob there; a BlobPathConflictException as for a write
    fun commit(path: BlobPath): BlobInfo

    // removes it; nothing after a commit
    fun discard()
}
