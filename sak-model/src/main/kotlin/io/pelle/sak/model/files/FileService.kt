package io.pelle.sak.model.files

import io.pelle.sak.model.SakDatabase
import java.awt.Color
import java.awt.RenderingHints
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

// a file of the file storage, addressed by the sha-256 of its content; uploadedBy is the address
// of the user that uploaded it first
data class StoredFile(
    val sha256: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
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

    // the ones of the hashes that are stored
    fun existing(sha256s: Collection<String>): Set<String> =
        sha256s.filter { SHA256.matches(it) }.toSet().let { if (it.isEmpty()) emptySet() else repository.existing(it) }

    // where the content of a stored file is
    fun path(file: StoredFile): Path = dir.resolve(file.sha256.take(2)).resolve(file.sha256)

    // a jpeg of at most THUMBNAIL_SIZE pixels on the longer side, the image itself when it is
    // smaller; null when the image can not be read
    @Synchronized
    fun thumbnail(file: StoredFile): Path? {
        val thumbnail = path(file).resolveSibling("${file.sha256}$THUMBNAIL_SUFFIX")
        if (Files.exists(thumbnail)) return thumbnail
        val image =
            try {
                ImageIO.read(path(file).toFile())
            } catch (_: IOException) {
                null
            } ?: return null
        val temp = Files.createTempFile(dir, "thumbnail-", ".tmp")
        try {
            ImageIO.write(scaled(image, THUMBNAIL_SIZE), "jpg", temp.toFile())
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

        // the types the storage takes, told by their first bytes
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
                else -> null
            }
        }
    }
}
