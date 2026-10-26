package io.pelle.sak.s3.storage

import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

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

class FilesystemBinaryStorage(
    private val rootPath: Path,
) : BinaryStorage {
    private val tmpDir = rootPath.resolve(BlobPath.STAGING_DIRECTORY)

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
        createParent(target, path)
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
        try {
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
    ): InputStream? {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }

        val file = resolve(path)
        if (!file.isRegularFile()) return null

        return try {
            val size = Files.size(file)
            val start = minOf(offset, size)
            val channel = Files.newByteChannel(file).position(start)
            LimitedInputStream(Channels.newInputStream(channel), minOf(length, size - start))
        } catch (_: NoSuchFileException) {
            null
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

    override fun delete(path: BlobPath) {
        val file = resolve(path)
        Files.deleteIfExists(file)
        pruneEmptyParents(file.parent)
    }

    override fun deleteTree(path: BlobPath) {
        val dir = resolve(path)
        if (dir.exists()) {
            Files.walk(dir).use { stream ->
                stream
                    .asSequence()
                    .toList()
                    .asReversed()
                    .forEach {
                        try {
                            Files.deleteIfExists(it)
                        } catch (_: DirectoryNotEmptyException) {
                        }
                    }
            }
        }
        pruneEmptyParents(dir.parent)
    }

    override fun list(prefix: BlobPath): List<BlobListEntry> {
        val dir = resolve(prefix)
        if (!dir.exists()) return emptyList()

        return Files
            .walk(dir)
            .use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() }
                    .mapNotNull { file ->
                        try {
                            BlobListEntry(
                                segments = dir.relativize(file).map { it.toString() },
                                size = Files.size(file),
                                lastModified = Files.getLastModifiedTime(file).toInstant(),
                            )
                        } catch (_: NoSuchFileException) {
                            null
                        }
                    }.toList()
            }.sortedBy { it.path }
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

    private fun pruneEmptyParents(from: Path?) {
        var current = from
        while (current != null && current != rootPath && current.startsWith(rootPath)) {
            if (!current.exists() || Files.list(current).use { it.findAny().isPresent }) return
            try {
                Files.deleteIfExists(current)
            } catch (_: DirectoryNotEmptyException) {
                return
            }
            current = current.parent
        }
    }
}
