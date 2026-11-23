package io.pelle.sak.backend.files

import io.pelle.sak.storage.InsufficientStorageException
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.isFree
import io.pelle.sak.backend.blobPath
import io.pelle.sak.backend.leftovers
import io.pelle.sak.core.logCause
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.storage.LocalStorage
import io.pelle.sak.storage.BlobContent
import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.FilesystemLocalStorage
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

sealed interface FileStoreResult {
    data class Stored(
        val file: StoredFile,
    ) : FileStoreResult

    data object TooLarge : FileStoreResult

    // the data volume has no room for more files
    data object NoRoom : FileStoreResult

    // the file is none the storage takes: what is wrong as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : FileStoreResult

    companion object {
        // only the types the storage knows by their first bytes are taken, and only when they can be read
        val UNSUPPORTED_TYPE = Invalid(listOf(ErrorMessage(FilesTexts.UNSUPPORTED_TYPE)))

        // an image larger than MAX_IMAGE_SIZE pixels on its longer side
        val IMAGE_TOO_LARGE = Invalid(listOf(ErrorMessage(FilesTexts.IMAGE_TOO_LARGE, "max" to FileService.MAX_IMAGE_SIZE)))

        // a recording longer than MAX_RECORDING_SECONDS
        val RECORDING_TOO_LONG = Invalid(listOf(ErrorMessage(FilesTexts.RECORDING_TOO_LONG, "max" to FileService.MAX_RECORDING_SECONDS / 60)))
    }
}

// the file storage of every app, the only way to the files. Every upload is a file with an id and a
// resource id; its content is a blob (MODEL-7) below the resource id (MODEL-8), each upload its own.
// The type comes from the first bytes, never from the uploader. Thumbnails are made on the first
// request and kept next to the content; at most one decode for each processor runs at a time, as a
// decoded photo takes up to 17 MB of the heap.
class FileService(
    private val repository: FileRepository,
    private val storage: LocalStorage,
    val maxSize: Long,
    // the room an upload may take on the data volume; none in a test that does not ask for one
    private val guard: WriteGuard? = null,
) {
    constructor(
        database: SakDatabase,
        dir: Path,
        clock: Clock = Clock.systemUTC(),
        maxSize: Long = DEFAULT_MAX_SIZE,
        guard: WriteGuard? = null,
    ) : this(database, FilesystemLocalStorage(dir), clock, maxSize, guard)

    // no resource id of a directory that is there without a file, e.g. from a restore of the instance
    private constructor(
        database: SakDatabase,
        storage: LocalStorage,
        clock: Clock,
        maxSize: Long,
        guard: WriteGuard?,
    ) : this(FileRepository(database, clock, free = storage::isFree), storage, maxSize, guard)

    // the file and size whose thumbnail is being made, by its resource id and the size
    private val thumbnailLocks = ConcurrentHashMap<String, Any>()

    // the decodes that may run at the same time, of different contents or sizes
    private val decodes = Semaphore(Runtime.getRuntime().availableProcessors())

    // a file of the owner, the address that uploaded it; another one's file is none
    fun byId(
        owner: String,
        id: Int,
    ): StoredFile? = repository.byId(owner, id)

    // the stored files of the owner among the ids by their id
    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): Map<Int, StoredFile> = if (ids.isEmpty()) emptyMap() else repository.byIds(owner, ids.toSet())

    internal fun setDiscovered(
        file: StoredFile,
        text: String,
    ): StoredFile {
        return repository.updateDiscovered(file.id, text) ?: file.copy(discovered = text)
    }

    // the content of a stored file, null when it is not in the storage
    fun content(file: StoredFile): BlobContent? = storage.content(blob(file.resourceId, CONTENT))

    // the directories of the storage that are named as a resource id no file has, e.g. left by a
    // restore of the instance (MODEL-8); they are kept, and no new file gets one of them
    fun leftovers(): List<String> = storage.leftovers(repository.resourceIds())

    // a jpeg of at most size (one of SCALED_SIZES) pixels on the longer side: an image scaled down,
    // a recording as its waveform. Null when the file can not be read; a marker next to it keeps it
    // from being decoded again. Each size is made once, one content and size decodes at a time
    fun thumbnail(
        file: StoredFile,
        size: Int = THUMBNAIL_SIZE,
    ): BlobContent? {
        require(size in SCALED_SIZES) { "no scaled size $size" }
        val thumbnail = blob(file.resourceId, thumbnailName(size))
        val none = blob(file.resourceId, NO_THUMBNAIL)
        storage.content(thumbnail)?.let { return it }
        if (storage.stat(none) != null) return null
        val key = "${file.resourceId}-$size"
        val lock = thumbnailLocks.computeIfAbsent(key) { Any() }
        try {
            synchronized(lock) {
                // made while waiting for the lock
                storage.content(thumbnail)?.let { return it }
                if (storage.stat(none) != null) return null
                decodes.acquire()
                val image =
                    try {
                        content(file)?.let { decodeThumbnail(it, file.contentType, size) }
                    } finally {
                        decodes.release()
                    }
                if (image == null) {
                    storage.write(none) {}
                    return null
                }
                // in memory, ImageIO would cache in files of its own
                storage.write(thumbnail) { out -> MemoryCacheImageOutputStream(out).use { ImageIO.write(image, "jpg", it) } }
                return storage.content(thumbnail)
            }
        } finally {
            // who waits for the lock, or takes a new one after this, finds the thumbnail made
            thumbnailLocks.remove(key, lock)
        }
    }

    private fun decodeThumbnail(
        content: BlobContent,
        contentType: String,
        size: Int,
    ): BufferedImage? =
        try {
            if (contentType.startsWith("audio/")) {
                Recordings.waveform(content)
            } else {
                Thumbnails.decode(content, size)?.let { Thumbnails.scaled(it, size) }
            }
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedAudioFileException) {
            null
        } catch (e: RuntimeException) {
            // a decoder that fails on what the header promised: no thumbnail, the marker keeps it
            // from being decoded with every request
            logger.warn(logCause(e)) { "no thumbnail of a file of the type $contentType, it can not be decoded" }
            null
        }

    // reads at most maxSize bytes into a blob of its own, then moves it below the resource id of the
    // new file
    fun store(
        content: InputStream,
        owner: String,
    ): FileStoreResult {
        // an upload is small (maxSize), so one look before it is enough
        try {
            guard?.requireRoom()
        } catch (_: InsufficientStorageException) {
            return FileStoreResult.NoRoom
        }
        val upload = BlobPath(listOf(UPLOADS, UUID.randomUUID().toString()))
        try {
            val head = ByteArray(HEAD_SIZE)
            var headLength = 0
            var size = 0L
            try {
                storage.write(upload) { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = content.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > maxSize) throw TooLargeException()
                        if (headLength < HEAD_SIZE) {
                            val take = minOf(read, HEAD_SIZE - headLength)
                            System.arraycopy(buffer, 0, head, headLength, take)
                            headLength += take
                        }
                        out.write(buffer, 0, read)
                    }
                }
            } catch (_: TooLargeException) {
                return FileStoreResult.TooLarge
            }
            val contentType = contentType(head.copyOf(headLength)) ?: return FileStoreResult.UNSUPPORTED_TYPE
            check(storage.content(upload)!!, contentType)?.let { return it }

            // the content is in place before the file is committed
            val file =
                repository.insert(contentType, size, owner) { resourceId ->
                    checkNotNull(storage.move(upload, blob(resourceId, CONTENT))) { "the upload is gone" }
                }
            return FileStoreResult.Stored(file)
        } finally {
            storage.delete(upload)
        }
    }

    private class TooLargeException : RuntimeException(null, null, false, false)

    companion object {
        // the most a stored file has, for every app (the request body of the apps and the limit of
        // caddy follow it, APPS_MAX_BODY_SIZE): a recording of the apps of MAX_RECORDING_SECONDS
        // (16 kHz mono 16 bit, 9.6 MB); the photos of the apps are jpegs of at most 2 MB
        const val DEFAULT_MAX_SIZE = 10L * 1024 * 1024

        // the largest photo (pixels of the longer side) and the longest recording the apps make; the
        // storage takes no more
        const val MAX_IMAGE_SIZE = 2048

        const val MAX_RECORDING_SECONDS = 300

        // why the content can not be stored, null when it can
        internal fun check(
            content: BlobContent,
            contentType: String,
        ): FileStoreResult? =
            if (contentType.startsWith("audio/")) {
                val format =
                    try {
                        // the reader marks and resets the stream
                        content.open().use { AudioSystem.getAudioFileFormat(BufferedInputStream(it)) }
                    } catch (_: UnsupportedAudioFileException) {
                        return FileStoreResult.UNSUPPORTED_TYPE
                    } catch (_: IOException) {
                        return FileStoreResult.UNSUPPORTED_TYPE
                    }
                val seconds = Recordings.recordingSeconds(format)
                when {
                    !Recordings.isRecording(format.format) -> FileStoreResult.UNSUPPORTED_TYPE
                    seconds == null -> FileStoreResult.UNSUPPORTED_TYPE
                    seconds > MAX_RECORDING_SECONDS -> FileStoreResult.RECORDING_TOO_LONG
                    else -> null
                }
            } else {
                val (width, height) = ImageMetadata.size(content, contentType) ?: return FileStoreResult.UNSUPPORTED_TYPE
                if (maxOf(width, height) > MAX_IMAGE_SIZE) FileStoreResult.IMAGE_TOO_LARGE else null
            }

        private const val BUFFER_SIZE = 64 * 1024

        // where an upload is written before its type is checked; no resource id, those have 12 characters
        private const val UPLOADS = "uploads"

        // the directory of a file: its content and what is kept next to it (e.g. its thumbnails)
        internal fun directory(resourceId: ResourceId) = resourceId.blobPath()

        internal fun blob(
            resourceId: ResourceId,
            name: String,
        ) = resourceId.blobPath(name)

        internal const val CONTENT = "content"

        internal fun thumbnailName(size: Int) = "thumbnail-$size.jpg"

        // next to a content that has no thumbnail, it can not be read
        internal const val NO_THUMBNAIL = "no-thumbnail"

        private const val HEAD_SIZE = 12

        const val THUMBNAIL_SIZE = 480

        // the sizes a photo is scaled to, the apps take the smallest that fills the place it shows
        // in on the screen; larger than the largest they take the file itself
        val SCALED_SIZES = listOf(128, 256, THUMBNAIL_SIZE, 1024)

        // the scaled size for one that was asked for, null for the file itself
        fun scaledSize(asked: Int): Int? = SCALED_SIZES.firstOrNull { it >= asked }

    }
}
