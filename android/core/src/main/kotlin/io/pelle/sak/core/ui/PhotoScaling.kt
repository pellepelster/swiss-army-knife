package io.pelle.sak.core.ui

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

// a photo the camera took or the user chose, as an app stores it: upright as it was taken, at most
// MAX_SIZE pixels on its longer side, a jpeg without the exif data, which can tell where it was
// taken (as the PhotoScaler of the web app)
object PhotoScaling {
    // the longest side of a stored photo (FileService.MAX_IMAGE_SIZE)
    private const val MAX_SIZE = 2048
    private const val JPEG_QUALITY = 85

    // an image too large for the memory of the phone is an IOException, as one that can not be read
    fun jpeg(
        context: Context,
        uri: Uri,
    ): ByteArray =
        try {
            scaled(context, uri)
        } catch (e: OutOfMemoryError) {
            throw IOException("$uri is too large", e)
        }

    private fun scaled(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val resolver = context.contentResolver
        // the stream opened anew for each read
        fun <T> read(block: (java.io.InputStream) -> T): T =
            (resolver.openInputStream(uri) ?: throw IOException("$uri can not be opened")).use(block)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("$uri is no image")
        // decoded at a power of two below its size, as small as still at least MAX_SIZE, and scaled
        // while it is decoded to MAX_SIZE on its longer side: a photo of 12 megapixels never is in
        // the memory whole (about 16 MB instead of 48 MB)
        val longer = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longer / (sample * 2) >= MAX_SIZE) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                if (longer / sample > MAX_SIZE) {
                    inScaled = true
                    inDensity = longer / sample
                    inTargetDensity = MAX_SIZE
                }
            }
        val decoded = read { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("$uri is no image")
        val orientation = read { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val factor = min(1f, MAX_SIZE.toFloat() / max(decoded.width, decoded.height))
        val matrix = upright(orientation).apply { preScale(factor, factor) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }
}

// the turn that makes a photo of the exif orientation upright, the mirrored ones too (a front camera
// or an editor writes them)
internal fun upright(orientation: Int): Matrix =
    Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
