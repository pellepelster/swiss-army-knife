package io.pelle.sak.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

// finds a QR code in the brightness of a picture of the camera, on the phone (zxing); also
// one light on dark, e.g. the web app in its dark mode. Not thread-safe: one for each analyzer
class QrDecoder {
    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.ALSO_INVERTED to true,
                ),
            )
        }

    // the text of the code in the picture, null when there is none; luminance holds a byte for each
    // pixel, rowStride bytes a row (at least width)
    fun decode(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
