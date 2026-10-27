package io.pelle.sak.model.files

import io.pelle.sak.model.SakDatabase
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.abs

// a file of the file storage, addressed by the sha-256 of its content; uploadedBy is the address
// of the user that uploaded it first, discovered the text discovered in it, null until then
data class StoredFile(
    val sha256: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val discovered: String? = null,
)

sealed interface StoreFileResult {
    data class Stored(
        val file: StoredFile,
    ) : StoreFileResult

    data object TooLarge : StoreFileResult

    // only the types the storage knows by their first bytes are taken
    data object UnsupportedType : StoreFileResult
}

// the generic file storage every application keeps its files in, the only way to them; the
// content lives in dir below the first two characters of its sha-256, the same content is stored
// once. The type is told by the first bytes, never taken from the uploader. The thumbnails of the
// images are made when they are first asked for and kept next to them.
class FileService internal constructor(
    private val repository: FileRepository,
    private val dir: Path,
    private val clock: Clock,
    val maxSize: Long,
) {
    constructor(
        database: SakDatabase,
        dir: Path,
        clock: Clock = Clock.systemUTC(),
        maxSize: Long = DEFAULT_MAX_SIZE,
    ) : this(FileRepository(database), dir, clock, maxSize)

    init {
        Files.createDirectories(dir)
    }

    fun bySha256(sha256: String): StoredFile? = sha256.takeIf { SHA256.matches(it) }?.let { repository.bySha256(it) }

    // the stored files of the hashes by their hash
    fun bySha256s(sha256s: Collection<String>): Map<String, StoredFile> =
        sha256s.filter { SHA256.matches(it) }.toSet().let { if (it.isEmpty()) emptyMap() else repository.bySha256s(it) }

    // the ones of the hashes that are stored
    fun existing(sha256s: Collection<String>): Set<String> =
        sha256s.filter { SHA256.matches(it) }.toSet().let { if (it.isEmpty()) emptySet() else repository.existing(it) }

    fun setDiscovered(
        file: StoredFile,
        text: String,
    ): StoredFile {
        val now = clock.instant()
        repository.updateDiscovered(file.sha256, text, now)
        return file.copy(discovered = text, updatedAt = now)
    }

    // where the content of a stored file is
    fun path(file: StoredFile): Path = dir.resolve(file.sha256.take(2)).resolve(file.sha256)

    // a jpeg of at most THUMBNAIL_SIZE pixels on the longer side: of an image the image itself,
    // scaled down when it is larger, of a recording its waveform; null when the file can not be read
    @Synchronized
    fun thumbnail(file: StoredFile): Path? {
        val thumbnail = path(file).resolveSibling("${file.sha256}$THUMBNAIL_SUFFIX")
        if (Files.exists(thumbnail)) return thumbnail
        val image =
            try {
                if (file.contentType.startsWith("audio/")) {
                    waveform(path(file))
                } else {
                    ImageIO.read(path(file).toFile())?.let { scaled(it, THUMBNAIL_SIZE) }
                }
            } catch (_: IOException) {
                null
            } catch (_: UnsupportedAudioFileException) {
                null
            } ?: return null
        val temp = Files.createTempFile(dir, "thumbnail-", ".tmp")
        try {
            ImageIO.write(image, "jpg", temp.toFile())
            Files.move(temp, thumbnail, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        return thumbnail
    }

    // reads the content to its end, at most maxSize bytes are taken
    fun store(
        content: InputStream,
        uploadedBy: String,
    ): StoreFileResult {
        val temp = Files.createTempFile(dir, "upload-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(HEAD_SIZE)
            var headLength = 0
            var size = 0L
            Files.newOutputStream(temp).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = content.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > maxSize) return StoreFileResult.TooLarge
                    if (headLength < HEAD_SIZE) {
                        val take = minOf(read, HEAD_SIZE - headLength)
                        System.arraycopy(buffer, 0, head, headLength, take)
                        headLength += take
                    }
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            val contentType = contentType(head.copyOf(headLength)) ?: return StoreFileResult.UnsupportedType

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val now = clock.instant()
            val file = StoredFile(sha256, contentType, size, uploadedBy, now, now)
            val target = path(file)
            if (!Files.exists(target)) {
                Files.createDirectories(target.parent)
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            return StoreFileResult.Stored(repository.insertIfMissing(file))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 10L * 1024 * 1024

        private const val BUFFER_SIZE = 64 * 1024

        private const val HEAD_SIZE = 12

        private val SHA256 = Regex("^[0-9a-f]{64}$")

        const val THUMBNAIL_SIZE = 480

        private const val THUMBNAIL_SUFFIX = ".thumbnail.jpg"

        private const val WAVEFORM_BARS = 48

        // the loudness of the recording in bars from its start to its end, on the 4:3 of the
        // photos; the loudest part reaches the full height, silence keeps a dot
        fun waveform(recording: Path): BufferedImage {
            val samples = AudioSystem.getAudioInputStream(recording.toFile()).use { samples(it) }
            val peaks =
                (0 until WAVEFORM_BARS).map { bar ->
                    val from = samples.size * bar / WAVEFORM_BARS
                    val to = maxOf(from + 1, samples.size * (bar + 1) / WAVEFORM_BARS)
                    (from until minOf(to, samples.size)).maxOfOrNull { abs(samples[it]) } ?: 0f
                }
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

        // the samples of the first channel from -1 to 1, of 8 or 16 bit pcm
        private fun samples(input: AudioInputStream): FloatArray {
            val format = input.format
            val bytes = input.readAllBytes()
            val frameSize = format.frameSize
            val bits = format.sampleSizeInBits
            if (format.encoding !in setOf(AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED) || bits !in setOf(8, 16)) {
                throw UnsupportedAudioFileException("only 8 and 16 bit pcm is drawn, not $format")
            }
            return FloatArray(bytes.size / frameSize) { frame ->
                val offset = frame * frameSize
                if (bits == 16) {
                    val low = bytes[offset + if (format.isBigEndian) 1 else 0].toInt() and 0xFF
                    val high = bytes[offset + if (format.isBigEndian) 0 else 1].toInt()
                    ((high shl 8) or low) / 32768f
                } else if (format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                    ((bytes[offset].toInt() and 0xFF) - 128) / 128f
                } else {
                    bytes[offset] / 128f
                }
            }
        }

        private val WAVEFORM_BACKGROUND = Color(0xF7, 0xF5, 0xEF)

        private val WAVEFORM_INK = Color(0x2B, 0x2A, 0x27)

        // halved step by step, a single step from a large image leaves it jagged; jpeg has no
        // transparency, it is drawn on white
        fun scaled(
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
        fun contentType(head: ByteArray): String? {
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
