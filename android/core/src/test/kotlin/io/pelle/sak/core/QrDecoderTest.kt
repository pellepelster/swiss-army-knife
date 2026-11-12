package io.pelle.sak.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.pelle.sak.core.qr.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// the decoder of the pictures of the camera, on the jvm: the brightness of a picture with a QR code
class QrDecoderTest {
    private val text = "https://sak.example.org/connect#token=" + "eyJhbGciOiJIUzI1NiJ9.".repeat(10) + "c2lnbmF0dXJl"

    // a picture of width x height, rows of rowStride bytes, the code in its middle; dark on light or
    // light on dark
    private fun picture(
        width: Int,
        height: Int,
        rowStride: Int,
        inverted: Boolean = false,
    ): ByteArray {
        val side = minOf(width, height) * 2 / 3
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        val (dark, light) = if (inverted) 230.toByte() to 20.toByte() else 20.toByte() to 230.toByte()
        val bytes = ByteArray(rowStride * height) { light }
        val left = (width - side) / 2
        val top = (height - side) / 2
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (code[x, y]) bytes[(top + y) * rowStride + left + x] = dark
            }
        }
        return bytes
    }

    @Test
    fun `a QR code is read from the brightness of a picture, with rows longer than the picture`() {
        assertEquals(text, QrDecoder().decode(picture(1280, 720, 1280), 1280, 1280, 720))
        assertEquals(text, QrDecoder().decode(picture(640, 480, 704), 704, 640, 480))
    }

    @Test
    fun `a QR code light on dark is read too, a picture without one is none`() {
        val decoder = QrDecoder()
        assertEquals(text, decoder.decode(picture(1280, 720, 1280, inverted = true), 1280, 1280, 720))
        assertNull(decoder.decode(ByteArray(640 * 480) { 128.toByte() }, 640, 640, 480))
        // the decoder is used again for the next picture
        assertEquals(text, decoder.decode(picture(1280, 720, 1280), 1280, 1280, 720))
    }
}
