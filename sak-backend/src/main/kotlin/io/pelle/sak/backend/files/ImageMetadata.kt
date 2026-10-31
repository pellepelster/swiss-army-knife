package io.pelle.sak.backend.files

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
}
