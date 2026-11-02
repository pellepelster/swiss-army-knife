package io.pelle.sak.storage

import io.pelle.sak.storage.model.BlobInfo
import io.pelle.sak.storage.model.BlobListEntry
import io.pelle.sak.storage.model.BlobUsage
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

// the blobs as files below the root, a segment of the path for each directory: the only code that
// writes binary data to disk (MODEL-7). A write is staged in STAGING_DIRECTORY and moved into place
// at once, a reader never sees half a blob
class FilesystemBinaryStorage(
    private val rootPath: Path,
    // a staged file older than this is left from a write that ended without its move (e.g. the
    // backend stopped), it is removed when the storage starts; a write that runs is younger
    staleAfter: Duration = DEFAULT_STALE_AFTER,
    private val clock: Clock = Clock.systemUTC(),
) : BinaryStorage {
    private val tmpDir = rootPath.resolve(BlobPath.STAGING_DIRECTORY)

    init {
        removeStale(staleAfter)
    }

    override fun write(
        path: BlobPath,
        content: InputStream,
    ): BlobInfo = write(path) { output -> content.use { it.copyTo(output) } }

    override fun write(
        path: BlobPath,
        content: (OutputStream) -> Unit,
    ): BlobInfo {
        val target = resolve(path)
        if (Files.isDirectory(target)) throw BlobPathConflictException(path)
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
        // the parent only now, a write that fails before leaves no directory behind
        try {
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

    override fun content(path: BlobPath): BlobContent? {
        val info = stat(path) ?: return null
        return BlobContent(info.size) {
            openRange(path, 0, info.size, info.size) ?: throw NoSuchFileException(path.toString())
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
        pruneEmptyParents(source.parent)
        return info(target)
    }

    override fun delete(path: BlobPath) {
        val file = resolve(path)
        Files.deleteIfExists(file)
        pruneEmptyParents(file.parent)
    }

    override fun deleteTree(path: BlobPath) {
        val dir = resolve(path)
        walk(dir, deleteDirectories = true) { file, _ -> Files.deleteIfExists(file) }
        pruneEmptyParents(dir.parent)
    }

    override fun list(prefix: BlobPath): List<BlobListEntry> {
        val dir = resolve(prefix)
        val entries = mutableListOf<BlobListEntry>()
        walk(dir) { file, attributes ->
            entries.add(BlobListEntry(dir.relativize(file).map { it.toString() }, attributes.size(), attributes.lastModifiedTime().toInstant()))
        }
        return entries.sortedBy { it.path }
    }

    override fun usage(prefix: BlobPath): BlobUsage {
        var blobs = 0L
        var bytes = 0L
        walk(resolve(prefix)) { _, attributes ->
            blobs++
            bytes += attributes.size()
        }
        return BlobUsage(blobs, bytes)
    }

    // every file below dir; a file or directory that goes while it is walked (another delete, a
    // prune) is left out, dir itself missing is none. With deleteDirectories, each directory is
    // deleted after its files, unless a write put a new blob in it meanwhile
    private fun walk(
        dir: Path,
        deleteDirectories: Boolean = false,
        visit: (Path, BasicFileAttributes) -> Unit,
    ) {
        if (!dir.exists()) return
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
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
                    if (deleteDirectories) {
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

    // the staged files older than staleAfter
    private fun removeStale(staleAfter: Duration) {
        val before = clock.instant().minus(staleAfter)
        walk(tmpDir) { file, attributes ->
            if (attributes.lastModifiedTime().toInstant().isBefore(before)) Files.deleteIfExists(file)
        }
    }

    private fun resolve(path: BlobPath): Path = path.segments.fold(rootPath) { current, component -> current.resolve(component) }

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
    }
}
