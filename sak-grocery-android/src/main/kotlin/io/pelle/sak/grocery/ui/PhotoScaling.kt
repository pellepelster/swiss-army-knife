package io.pelle.sak.grocery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

// a photo the camera took or the user chose, as it is stored: upright as it was taken, at most
// MAX_SIZE pixels on its longer side, a jpeg without the exif data, which can tell where it was
// taken (as the PhotoScaler of the web app)
object PhotoScaling {
    // the longest side of a stored photo (FileService.MAX_IMAGE_SIZE)
    private const val MAX_SIZE = 2048
    private const val JPEG_QUALITY = 85

    fun jpeg(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val resolver = context.contentResolver
        fun <T> read(block: (java.io.InputStream) -> T): T = resolver.openInputStream(uri)?.use(block) ?: throw IOException("$uri can not be read")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("$uri is no image")
        // decoded at a power of two below its size, as small as still at least MAX_SIZE
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
        val decoded =
            read { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IOException("$uri is no image")
        val degrees =
            when (read { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        val factor = min(1f, MAX_SIZE.toFloat() / max(decoded.width, decoded.height))
        val matrix =
            Matrix().apply {
                postScale(factor, factor)
                postRotate(degrees)
            }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }
}
