package io.pelle.sak.model.files

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

// what the headers of the images the file storage takes tell, read without decoding the image
internal object ImageMetadata {
    // width and height, null when the image can not be read
    fun size(
        image: Path,
        contentType: String,
    ): Pair<Int, Int>? =
        try {
            if (contentType == "image/webp") webpSize(image) else imageIoSize(image)
        } catch (_: IOException) {
            null
        }

    // exif or xmp metadata, which can tell where the image was taken; gif has none
    fun hasMetadata(
        image: Path,
        contentType: String,
    ): Boolean =
        when (contentType) {
            "image/jpeg" -> jpegHasMetadata(image)
            "image/png" -> pngHasMetadata(image)
            "image/webp" -> riffChunks(image).any { it == "EXIF" || it == "XMP " }
            else -> false
        }

    private fun imageIoSize(image: Path): Pair<Int, Int>? =
        ImageIO.createImageInputStream(image.toFile())?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }

    // the canvas of an extended webp, or the frame of a lossy or lossless one
    private fun webpSize(image: Path): Pair<Int, Int>? {
        val head = Files.newInputStream(image).use { it.readNBytes(30) }
        if (head.size < 30) return null
        val bytes = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        fun uint24(offset: Int) = (head[offset].toInt() and 0xFF) or ((head[offset + 1].toInt() and 0xFF) shl 8) or ((head[offset + 2].toInt() and 0xFF) shl 16)
        return when (String(head, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> uint24(24) + 1 to uint24(27) + 1
            "VP8 " -> (bytes.getShort(26).toInt() and 0x3FFF) to (bytes.getShort(28).toInt() and 0x3FFF)
            "VP8L" -> {
                val bits = bytes.getInt(21)
                (bits and 0x3FFF) + 1 to ((bits ushr 14) and 0x3FFF) + 1
            }
            else -> null
        }
    }

    // an app1 segment, exif or xmp, before the image data starts
    private fun jpegHasMetadata(image: Path): Boolean =
        DataInputStream(Files.newInputStream(image).buffered()).use { input ->
            try {
                // past the start of image, up to the start of the image data
                input.readUnsignedShort()
                var marker = 0
                while (marker != 0xDA && marker != 0xD9 && marker != 0xE1) {
                    if (input.readUnsignedByte() != 0xFF) return false
                    marker = input.readUnsignedByte()
                    // the markers without a length
                    if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 || marker == 0xFF) continue
                    if (marker != 0xDA && marker != 0xD9 && marker != 0xE1) input.skipNBytes(input.readUnsignedShort() - 2L)
                }
                marker == 0xE1
            } catch (_: EOFException) {
                false
            }
        }

    // an exif chunk or xmp in an international text chunk
    private fun pngHasMetadata(image: Path): Boolean =
        DataInputStream(Files.newInputStream(image).buffered()).use { input ->
            try {
                input.skipNBytes(8)
                var type = ""
                var found = false
                while (!found && type != "IEND") {
                    val length = input.readInt().toLong() and 0xFFFFFFFFL
                    type = String(input.readNBytes(4), Charsets.US_ASCII)
                    if (type == "iTXt") {
                        val keyword = input.readNBytes(minOf(length, XMP_KEYWORD.length.toLong()).toInt())
                        found = String(keyword, Charsets.ISO_8859_1) == XMP_KEYWORD
                        input.skipNBytes(length - keyword.size + 4)
                    } else {
                        found = type == "eXIf"
                        input.skipNBytes(length + 4)
                    }
                }
                found
            } catch (_: EOFException) {
                false
            }
        }

    private const val XMP_KEYWORD = "XML:com.adobe.xmp"

    // the types of the chunks of a riff file
    private fun riffChunks(image: Path): List<String> =
        DataInputStream(Files.newInputStream(image).buffered()).use { input ->
            val chunks = mutableListOf<String>()
            try {
                input.skipNBytes(12)
                while (true) {
                    val type = String(input.readNBytes(4), Charsets.US_ASCII)
                    if (type.length < 4) break
                    chunks += type
                    val length = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
                    input.skipNBytes(length + (length and 1))
                }
            } catch (_: EOFException) {
                // the chunks so far
            }
            chunks
        }
}
