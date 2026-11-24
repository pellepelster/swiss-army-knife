package io.pelle.sak.backend.files

import io.pelle.sak.storage.model.BlobContent
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

// the thumbnails of the images of the file storage: an image read at a fraction of its size and
// scaled down
object Thumbnails {
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
}
