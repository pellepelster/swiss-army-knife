package io.pelle.sak.backend.files

import io.pelle.sak.api.logCause
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobContent
import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.FilesystemBinaryStorage
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
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

sealed interface StoreFileResult {
    data class Stored(
        val file: StoredFile,
    ) : StoreFileResult

    data object TooLarge : StoreFileResult

    // only the types the storage knows by their first bytes are taken, and only when they can be read
    data object UnsupportedType : StoreFileResult

    // an image larger than MAX_IMAGE_SIZE pixels on its longer side
    data object ImageTooLarge : StoreFileResult


    // a recording longer than MAX_RECORDING_SECONDS
    data object RecordingTooLong : StoreFileResult
}

// the file storage of every app, the only way to the files. Every upload is a file with an id; its
// content is a blob (MODEL-7) below the first two characters of its sha-256, stored once. The type
// comes from the first bytes, never from the uploader. Thumbnails are made on the first request and
// kept next to the blob; at most one decode for each processor runs at a time, as a decoded photo
// takes up to 17 MB of the heap.
class FileService(
    private val repository: FileRepository,
    private val dir: Path,
    val maxSize: Long,
) {
    constructor(
        database: SakDatabase,
        dir: Path,
        clock: Clock = Clock.systemUTC(),
        maxSize: Long = DEFAULT_MAX_SIZE,
    ) : this(FileRepository(database, clock), dir, maxSize)

    // the content and size whose thumbnail is being made, by its sha-256 and the size
    private val thumbnailLocks = ConcurrentHashMap<String, Any>()

    // the decodes that may run at the same time, of different contents or sizes
    private val decodes = Semaphore(Runtime.getRuntime().availableProcessors())

    private val storage: BinaryStorage = FilesystemBinaryStorage(dir)

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
    fun content(file: StoredFile): BlobContent? = storage.content(blob(file.sha256))

    // a jpeg of at most size (one of SCALED_SIZES) pixels on the longer side: an image scaled down,
    // a recording as its waveform. Null when the file can not be read; a marker next to it keeps it
    // from being decoded again. Each size is made once, one content and size decodes at a time
    fun thumbnail(
        file: StoredFile,
        size: Int = THUMBNAIL_SIZE,
    ): BlobContent? {
        require(size in SCALED_SIZES) { "no scaled size $size" }
        val suffix = if (size == THUMBNAIL_SIZE) THUMBNAIL_SUFFIX else ".thumbnail-$size.jpg"
        val thumbnail = blob(file.sha256, suffix)
        val none = blob(file.sha256, NO_THUMBNAIL_SUFFIX)
        storage.content(thumbnail)?.let { return it }
        if (storage.stat(none) != null) return null
        val key = "${file.sha256}-$size"
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
                waveform(content)
            } else {
                decode(content, size)?.let { scaled(it, size) }
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

    // reads at most maxSize bytes into a blob of its own while the sha-256 is computed, then moves
    // it to the path of the sha-256
    fun store(
        content: InputStream,
        owner: String,
    ): StoreFileResult {
        val upload = BlobPath(listOf(UPLOADS, UUID.randomUUID().toString()))
        try {
            val digest = MessageDigest.getInstance("SHA-256")
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
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            } catch (_: TooLargeException) {
                return StoreFileResult.TooLarge
            }
            val contentType = contentType(head.copyOf(headLength)) ?: return StoreFileResult.UnsupportedType
            check(storage.content(upload)!!, contentType)?.let { return it }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            // the content is in place before the file refers to it
            val target = blob(sha256)
            if (storage.stat(target) == null) storage.move(upload, target)
            return StoreFileResult.Stored(repository.insert(sha256, contentType, size, owner))
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

        // null when the header leaves the number or the rate of the frames open
        // (AudioSystem.NOT_SPECIFIED)
        fun recordingSeconds(format: AudioFileFormat): Float? =
            format.takeIf { it.frameLength >= 0 && it.format.frameRate > 0 }?.let { it.frameLength / it.format.frameRate }

        // whether the recording is one the apps record or close to it: pcm of 8 or 16 bit, mono or
        // stereo. The header is of the uploader; what reads a recording (the waveform) sizes its
        // buffers from it, so nothing else is stored
        fun isRecording(format: AudioFormat): Boolean =
            format.encoding in PCM &&
                format.sampleSizeInBits in RECORDING_BITS &&
                format.channels in 1..MAX_RECORDING_CHANNELS &&
                format.frameSize == format.channels * format.sampleSizeInBits / 8

        private val PCM = setOf(AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED)

        private val RECORDING_BITS = setOf(8, 16)

        private const val MAX_RECORDING_CHANNELS = 2

        // why the content can not be stored, null when it can
        internal fun check(
            content: BlobContent,
            contentType: String,
        ): StoreFileResult? =
            if (contentType.startsWith("audio/")) {
                val format =
                    try {
                        // the reader marks and resets the stream
                        content.open().use { AudioSystem.getAudioFileFormat(BufferedInputStream(it)) }
                    } catch (_: UnsupportedAudioFileException) {
                        return StoreFileResult.UnsupportedType
                    } catch (_: IOException) {
                        return StoreFileResult.UnsupportedType
                    }
                val seconds = recordingSeconds(format)
                when {
                    !isRecording(format.format) -> StoreFileResult.UnsupportedType
                    seconds == null -> StoreFileResult.UnsupportedType
                    seconds > MAX_RECORDING_SECONDS -> StoreFileResult.RecordingTooLong
                    else -> null
                }
            } else {
                val (width, height) = ImageMetadata.size(content, contentType) ?: return StoreFileResult.UnsupportedType
                if (maxOf(width, height) > MAX_IMAGE_SIZE) StoreFileResult.ImageTooLarge else null
            }

        private const val BUFFER_SIZE = 64 * 1024

        // where an upload is written before its sha-256 is known
        private const val UPLOADS = "uploads"

        // the path of a content and of what is kept next to it (e.g. its thumbnails)
        private fun blob(
            sha256: String,
            suffix: String = "",
        ) = BlobPath(listOf(sha256.take(2), sha256 + suffix))

        private const val HEAD_SIZE = 12

        const val THUMBNAIL_SIZE = 480

        // the sizes a photo is scaled to, the apps take the smallest that fills the place it shows
        // in on the screen; larger than the largest they take the file itself
        val SCALED_SIZES = listOf(128, 256, THUMBNAIL_SIZE, 1024)

        // the scaled size for one that was asked for, null for the file itself
        fun scaledSize(asked: Int): Int? = SCALED_SIZES.firstOrNull { it >= asked }

        private const val THUMBNAIL_SUFFIX = ".thumbnail.jpg"

        // next to a content that has no thumbnail, it can not be read
        private const val NO_THUMBNAIL_SUFFIX = ".no-thumbnail"

        private const val WAVEFORM_BARS = 48

        // subsampled when the image is at least SUBSAMPLED_SIZE times the size, which takes a
        // fraction of the memory (2048 pixels for 128 are read as 512: 1 MB instead of 17 MB); the
        // halving steps of scaled() smooth the aliasing. Null when no reader knows the image
        internal fun decode(
            image: BlobContent,
            size: Int,
        ): BufferedImage? =
            image.open().use { stream ->
                MemoryCacheImageInputStream(stream).use { input -> read(input, size) }
            }

        private fun read(
            input: ImageInputStream,
            size: Int,
        ): BufferedImage? {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                // as ImageIO.read
                reader.setInput(input, true, true)
                val param = reader.defaultReadParam
                val subsampling = subsampling(maxOf(reader.getWidth(0), reader.getHeight(0)), size)
                if (subsampling > 1) param.setSourceSubsampling(subsampling, subsampling, 0, 0)
                return reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }

        // the largest power of 2 that keeps the longer side at least SUBSAMPLED_SIZE times size
        internal fun subsampling(
            longer: Int,
            size: Int,
        ): Int {
            var subsampling = 1
            while (longer / (subsampling * 2) >= size * SUBSAMPLED_SIZE) subsampling *= 2
            return subsampling
        }

        private const val SUBSAMPLED_SIZE = 4

        // the loudness of the recording in bars from its start to its end, on the 4:3 of the
        // photos; the loudest part reaches the full height, silence keeps a dot
        internal fun waveform(recording: BlobContent): BufferedImage {
            // the reader marks and resets the stream
            val peaks = AudioSystem.getAudioInputStream(BufferedInputStream(recording.open())).use { peaks(it) }
            val loudest = peaks.max().takeIf { it > 0f } ?: 1f
            val width = THUMBNAIL_SIZE
            val height = THUMBNAIL_SIZE * 3 / 4
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = WAVEFORM_BACKGROUND
                fillRect(0, 0, width, height)
                color = WAVEFORM_INK
                val step = width.toDouble() / (WAVEFORM_BARS + 2)
                val bar = step * 0.6
                peaks.forEachIndexed { index, peak ->
                    val barHeight = maxOf(bar, peak / loudest * height * 0.7)
                    val x = step * (index + 1) + (step - bar) / 2
                    fill(RoundRectangle2D.Double(x, (height - barHeight) / 2, bar, barHeight, bar, bar))
                }
                dispose()
            }
            return image
        }

        // the largest absolute sample of the first channel (from 0 to 1, of 8 or 16 bit pcm) in
        // each of the WAVEFORM_BARS bars, read block by block; a bar is at least one frame, a
        // recording of fewer frames than bars has bars that share a frame
        private fun peaks(input: AudioInputStream): List<Float> {
            val format = input.format
            val frameSize = format.frameSize
            val bits = format.sampleSizeInBits
            // also of a recording stored before only these were taken
            if (!isRecording(format)) throw UnsupportedAudioFileException("only mono and stereo pcm of 8 and 16 bit is drawn, not $format")
            val frames = input.frameLength
            if (frames < 0) throw UnsupportedAudioFileException("the number of frames is not known")
            val from = LongArray(WAVEFORM_BARS) { frames * it / WAVEFORM_BARS }
            val to = LongArray(WAVEFORM_BARS) { maxOf(from[it] + 1, frames * (it + 1) / WAVEFORM_BARS) }
            val peaks = FloatArray(WAVEFORM_BARS)
            // the first bar that has not ended before the frame
            var first = 0
            var frame = 0L
            // whole frames; its size is bounded in bytes, the size of a frame is of the header
            val block = ByteArray(frameSize * maxOf(1, WAVEFORM_BLOCK_SIZE / frameSize))
            while (true) {
                val read = input.readNBytes(block, 0, block.size)
                if (read < frameSize) break
                for (offset in 0..read - frameSize step frameSize) {
                    val sample =
                        if (bits == 16) {
                            val low = block[offset + if (format.isBigEndian) 1 else 0].toInt() and 0xFF
                            val high = block[offset + if (format.isBigEndian) 0 else 1].toInt()
                            ((high shl 8) or low) / 32768f
                        } else if (format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                            ((block[offset].toInt() and 0xFF) - 128) / 128f
                        } else {
                            block[offset] / 128f
                        }
                    while (first < WAVEFORM_BARS && to[first] <= frame) first++
                    var bar = first
                    while (bar < WAVEFORM_BARS && from[bar] <= frame) {
                        if (frame < to[bar]) peaks[bar] = maxOf(peaks[bar], abs(sample))
                        bar++
                    }
                    frame++
                }
                if (read < block.size) break
            }
            return peaks.toList()
        }

        private const val WAVEFORM_BLOCK_SIZE = 64 * 1024

        private val WAVEFORM_BACKGROUND = Color(0xF7, 0xF5, 0xEF)

        private val WAVEFORM_INK = Color(0x2B, 0x2A, 0x27)

        // halved step by step, a single step from a large image leaves it jagged; jpeg has no
        // transparency, it is drawn on white
        internal fun scaled(
            image: BufferedImage,
            size: Int,
        ): BufferedImage {
            val factor = minOf(1.0, size.toDouble() / maxOf(image.width, image.height))
            val width = maxOf(1, (image.width * factor).toInt())
            val height = maxOf(1, (image.height * factor).toInt())
            var current = image
            do {
                val stepWidth = maxOf(width, current.width / 2)
                val stepHeight = maxOf(height, current.height / 2)
                val step = BufferedImage(stepWidth, stepHeight, BufferedImage.TYPE_INT_RGB)
                step.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(current, 0, 0, stepWidth, stepHeight, Color.WHITE, null)
                    dispose()
                }
                current = step
            } while (current.width != width || current.height != height)
            return current
        }

        // the types the storage takes, told by their first bytes: images and wav recordings
        internal fun contentType(head: ByteArray): String? {
            fun startsWith(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
            fun ascii(
                offset: Int,
                text: String,
            ) = head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
            return when {
                startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
                ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
                ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
                else -> null
            }
        }
    }
}
