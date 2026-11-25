package io.pelle.sak.storage

import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.api.storage.StagedWrite
import io.pelle.sak.api.storage.model.BlobPathConflictException
import io.pelle.sak.api.storage.model.BlobChangedException
import io.pelle.sak.api.storage.model.BlobInfo
import io.pelle.sak.api.storage.KeyOrderEntry
import io.pelle.sak.api.storage.Walk
import io.pelle.sak.api.storage.model.BlobListEntry
import io.pelle.sak.api.storage.model.BlobPath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private class NonClosingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(byte: Int) = delegate.write(byte)

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) = delegate.write(buffer, offset, length)

    override fun flush() = delegate.flush()

    override fun close() = delegate.flush()
}

private class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val read = delegate.read()
        if (read >= 0) remaining--
        return read
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining <= 0) return -1
        val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    override fun available() = minOf(delegate.available().toLong(), remaining).toInt()

    override fun close() = delegate.close()
}

// the blobs as files below the root, a directory for each segment of the path (MODEL-7). A write
// is staged in STAGING_DIRECTORY and moved into place at once, a reader never sees half a blob
class FilesystemLocalStorage(
    private val rootPath: Path,
    // a staged file older than this is left from a write that ended without its move (e.g. the
    // backend stopped), it is removed when the storage starts; a write that runs is younger
    staleAfter: Duration = DEFAULT_STALE_AFTER,
    private val clock: Clock = Clock.systemUTC(),
) : LocalStorage {
    private val tmpDir = rootPath.resolve(STAGING_DIRECTORY)

    init {
        removeStale(clock.instant().minus(staleAfter))
    }

    override fun write(
        path: BlobPath,
        content: (OutputStream) -> Unit,
    ): BlobInfo {
        if (Files.isDirectory(resolve(path))) throw BlobPathConflictException(path)
        return commit(staged(content), path)
    }

    override fun stage(content: InputStream): StagedWrite {
        val staged = staged { output -> content.use { it.copyTo(output) } }
        return object : StagedWrite {
            override val info = info(staged)

            override fun commit(path: BlobPath) = commit(staged, path)

            override fun discard() {
                Files.deleteIfExists(staged)
            }
        }
    }

    // the staged files older than before, as a write that never ended leaves them
    override fun removeStale(before: Instant): Int {
        var removed = 0
        walk(tmpDir) { file, attributes ->
            if (attributes.lastModifiedTime().toInstant().isBefore(before) && Files.deleteIfExists(file)) removed++
        }
        return removed
    }

    // what content writes, in a file of its own in the staging directory, on the disk
    private fun staged(content: (OutputStream) -> Unit): Path {
        Files.createDirectories(tmpDir)
        val staged = tmpDir.resolve(UUID.randomUUID().toString())
        try {
            FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW).use { channel ->
                val output = Channels.newOutputStream(channel)
                content(NonClosingOutputStream(output))
                output.flush()
                channel.force(true)
            }
        } catch (e: Throwable) {
            Files.deleteIfExists(staged)
            throw e
        }
        return staged
    }

    // the staged file moved to the path at once; the parent only now, a write that fails before leaves
    // no directory behind. A failed move removes the staged file
    private fun commit(
        staged: Path,
        path: BlobPath,
    ): BlobInfo {
        val target = resolve(path)
        try {
            if (Files.isDirectory(target)) throw BlobPathConflictException(path)
            createParent(target, path)
            moveIntoPlace(staged, target, path)
        } catch (e: Throwable) {
            Files.deleteIfExists(staged)
            throw e
        }
        fsyncDirectory(target.parent)
        return info(target)
    }

    override fun open(path: BlobPath): InputStream? {
        val file = resolve(path)
        if (!file.isRegularFile()) return null
        return try {
            Files.newInputStream(file)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override fun openRange(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long?,
    ): InputStream? {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }

        val file = resolve(path)
        if (!file.isRegularFile()) return null

        val channel =
            try {
                FileChannel.open(file, StandardOpenOption.READ)
            } catch (_: NoSuchFileException) {
                return null
            }
        try {
            // the size of the file opened: a blob is replaced as a whole, never changed in place
            val opened = channel.size()
            if (size != null && opened != size) throw BlobChangedException(path, size, opened)
            val start = minOf(offset, opened)
            channel.position(start)
            return LimitedInputStream(Channels.newInputStream(channel), minOf(length, opened - start))
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    override fun stat(path: BlobPath): BlobInfo? {
        val file = resolve(path)
        if (!file.isRegularFile()) return null
        return try {
            info(file)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override fun move(
        from: BlobPath,
        to: BlobPath,
    ): BlobInfo? {
        val source = resolve(from)
        if (!source.isRegularFile()) return null
        val target = resolve(to)
        if (Files.isDirectory(target)) throw BlobPathConflictException(to)
        createParent(target, to)
        try {
            moveIntoPlace(source, target, to)
        } catch (_: NoSuchFileException) {
            // moved or deleted by another meanwhile
            return null
        }
        fsyncDirectory(target.parent)
        // the directory the blob left too, so after a crash it shows at one path only
        if (source.parent != target.parent) {
            try {
                fsyncDirectory(source.parent)
            } catch (_: NoSuchFileException) {
                // pruned by a delete of another meanwhile
            }
        }
        pruneEmptyParents(source.parent)
        return info(target)
    }

    override fun delete(path: BlobPath): Boolean {
        val file = resolve(path)
        val deleted = Files.deleteIfExists(file)
        pruneEmptyParents(file.parent)
        return deleted
    }

    override fun deleteTree(path: BlobPath) {
        val dir = resolve(path)
        walk(dir, deleteDirectories = true) { file, _ -> Files.deleteIfExists(file) }
        pruneEmptyParents(dir.parent)
    }

    override fun forEach(
        prefix: BlobPath,
        visit: (BlobListEntry) -> Unit,
    ) {
        val dir = resolve(prefix)
        // a blob at the prefix is not below it (ST-3)
        if (!Files.isDirectory(dir)) return
        walk(dir) { file, attributes ->
            visit(BlobListEntry(dir.relativize(file).map { it.toString() }, attributes.size(), attributes.lastModifiedTime().toInstant()))
        }
    }

    override fun walkInKeyOrder(
        root: BlobPath,
        below: List<String>,
        startAfter: String?,
        skipUnder: String?,
        visit: (KeyOrderEntry) -> Walk,
    ) {
        val dir = resolve(root.resolve(below))
        if (Files.isDirectory(dir)) walkSorted(dir, below, startAfter, skipUnder, visit)
    }

    // the entries of one directory in the order of the keys, a directory sorted as its name and a slash
    // (a-b before a/b, as in a key '-' is before '/'), each directory before what is below it; false
    // once the walk is to end
    private fun walkSorted(
        dir: Path,
        segments: List<String>,
        startAfter: String?,
        skipUnder: String?,
        visit: (KeyOrderEntry) -> Walk,
    ): Boolean {
        val names =
            try {
                Files.list(dir).use { entries -> entries.filter { it != tmpDir }.toList() }
            } catch (_: NoSuchFileException) {
                return true
            }
        val sorted = names.map { it to Files.isDirectory(it) }.sortedBy { (path, isDirectory) -> path.fileName.toString() + if (isDirectory) "/" else "" }
        for ((path, isDirectory) in sorted) {
            val below = segments + path.fileName.toString()
            val key = below.joinToString("/")
            if (isDirectory) {
                val prefix = "$key/"
                // every key below it is not after startAfter, or is to be skipped
                if (startAfter != null && prefix < startAfter && !startAfter.startsWith(prefix)) continue
                if (skipUnder != null && prefix.startsWith(skipUnder)) continue
                when (visit(KeyOrderEntry.Directory(below))) {
                    Walk.STOP -> return false
                    Walk.SKIP -> continue
                    Walk.CONTINUE -> if (!walkSorted(path, below, startAfter, skipUnder, visit)) return false
                }
            } else {
                if (startAfter != null && key <= startAfter) continue
                if (skipUnder != null && key.startsWith(skipUnder)) continue
                val attributes =
                    try {
                        Files.readAttributes(path, BasicFileAttributes::class.java)
                    } catch (_: NoSuchFileException) {
                        // deleted since the directory was read
                        continue
                    }
                if (!attributes.isRegularFile) continue
                if (visit(KeyOrderEntry.Blob(BlobListEntry(below, attributes.size(), attributes.lastModifiedTime().toInstant()))) == Walk.STOP) return false
            }
        }
        return true
    }

    override fun children(path: BlobPath): List<String> {
        val dir = resolve(path)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { entries -> entries.filter { it != tmpDir }.map { it.fileName.toString() }.toList() }.sorted()
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }

    // every file below dir; a file or directory that goes while it is walked (another delete, a
    // prune) is left out, dir itself missing is none. With deleteDirectories, each directory is
    // deleted after its files, unless a write put a new blob in it meanwhile, the root never. The
    // staging directory is no blob: it is walked only when it is dir
    private fun walk(
        dir: Path,
        deleteDirectories: Boolean = false,
        visit: (Path, BasicFileAttributes) -> Unit,
    ) {
        if (!dir.exists()) return
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult = if (directory == tmpDir && dir != tmpDir) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isRegularFile) visit(file, attributes)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exception: IOException,
                ): FileVisitResult = if (exception is NoSuchFileException) FileVisitResult.CONTINUE else throw exception

                override fun postVisitDirectory(
                    directory: Path,
                    exception: IOException?,
                ): FileVisitResult {
                    if (exception != null && exception !is NoSuchFileException) throw exception
                    if (deleteDirectories && directory != rootPath) {
                        try {
                            Files.deleteIfExists(directory)
                        } catch (_: DirectoryNotEmptyException) {
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    override fun directory(path: BlobPath): Path = resolve(path)

    // the rules of the file system of the volume, besides those of every path (BlobPath)
    private fun resolve(path: BlobPath): Path {
        require(path.segments.firstOrNull() != STAGING_DIRECTORY) { "'$STAGING_DIRECTORY' is reserved for staged writes" }
        path.segments.forEach { segment ->
            require(segment.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) { "the segment '$segment' is longer than a name of a file" }
        }
        return path.segments.fold(rootPath) { current, component -> current.resolve(component) }
    }

    // a blob on the way to the target (e.g. the blob 'a' for the path 'a/b') makes the
    // directory creation fail
    private fun createParent(
        target: Path,
        path: BlobPath,
    ) {
        try {
            Files.createDirectories(target.parent)
        } catch (_: FileAlreadyExistsException) {
            throw BlobPathConflictException(path)
        }
    }

    private fun moveIntoPlace(
        staged: Path,
        target: Path,
        path: BlobPath,
    ) {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: NoSuchFileException) {
            // a concurrent delete pruned the parent in the meantime
            createParent(target, path)
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fsyncDirectory(dir: Path) {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun info(file: Path) = BlobInfo(Files.size(file), Files.getLastModifiedTime(file).toInstant())

    // removes the directories from 'from' up that are empty; another prune or delete at the same
    // time may remove one first, which ends this one
    private fun pruneEmptyParents(from: Path?) {
        var current = from
        while (current != null && current != rootPath && current.startsWith(rootPath)) {
            try {
                if (Files.list(current).use { it.findAny().isPresent }) return
                Files.delete(current)
            } catch (_: NoSuchFileException) {
                return
            } catch (_: DirectoryNotEmptyException) {
                return
            }
            current = current.parent
        }
    }

    companion object {
        val DEFAULT_STALE_AFTER: Duration = Duration.ofHours(1)

        // where a write is staged, below the root: no path of a blob starts with it
        const val STAGING_DIRECTORY = ".tmp"

        // the longest name of a file the file systems of the data volume take, in bytes of UTF-8: a
        // longer segment is refused before a write, not when the blob is moved into place
        const val MAX_SEGMENT_BYTES = 255
    }
}
