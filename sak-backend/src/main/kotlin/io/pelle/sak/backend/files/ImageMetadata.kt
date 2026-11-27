package io.pelle.sak.backend.files

import io.pelle.sak.api.storage.model.internal.BlobContent
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

// what the headers of the images the file storage takes tell, read without decoding the image
internal object ImageMetadata {
    // width and height, null when the image can not be read
    fun size(
        image: BlobContent,
        contentType: String,
    ): Pair<Int, Int>? =
        try {
            if (contentType == "image/webp") webpSize(image) else imageIoSize(image)
        } catch (_: IOException) {
            null
        }

    // in memory, ImageIO would cache in files of its own
    private fun imageIoSize(image: BlobContent): Pair<Int, Int>? =
        image.open().use { stream ->
            MemoryCacheImageInputStream(stream).use { input -> imageIoSize(input) }
        }

    private fun imageIoSize(input: ImageInputStream): Pair<Int, Int>? {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            return reader.getWidth(0) to reader.getHeight(0)
        } finally {
            reader.dispose()
        }
    }

    // the canvas of an extended webp, or the frame of a lossy or lossless one
    private fun webpSize(image: BlobContent): Pair<Int, Int>? {
        val head = image.open().use { it.readNBytes(30) }
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

    // the scans of a jpeg, at most limit + 1: a baseline jpeg has one, a progressive one some ten. Each
    // scan is a pass of the decoder over the whole image, so a jpeg of many small scans takes long to
    // decode (SEC-50). Null when its markers can not be read
    fun jpegScans(
        image: BlobContent,
        limit: Int,
    ): Int? =
        try {
            image.open().use { jpegScans(BufferedInputStream(it), limit) }
        } catch (_: IOException) {
            null
        }

    private fun jpegScans(
        input: InputStream,
        limit: Int,
    ): Int? {
        fun next(): Int = input.read().also { if (it < 0) throw EOFException() }
        if (next() != 0xFF || next() != 0xD8) return null
        var scans = 0
        // the marker read at the end of the entropy coded data of a scan
        var pending: Int? = null
        while (true) {
            val marker =
                pending ?: run {
                    if (next() != 0xFF) return null
                    var byte = next()
                    // fill bytes
                    while (byte == 0xFF) byte = next()
                    byte
                }
            pending = null
            when (marker) {
                // the end of the image
                0xD9 -> return scans
                // markers without a length
                0x01, in 0xD0..0xD7 -> continue
            }
            val length = (next() shl 8) or next()
            if (length < 2) return null
            input.skipNBytes((length - 2).toLong())
            if (marker != 0xDA) continue
            if (++scans > limit) return scans
            // the entropy coded data: a 0xFF is a stuffed zero, a restart or the next marker
            while (pending == null) {
                if (next() != 0xFF) continue
                var byte = next()
                while (byte == 0xFF) byte = next()
                if (byte != 0x00 && byte !in 0xD0..0xD7) pending = byte
            }
        }
    }
}
