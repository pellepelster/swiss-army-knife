package io.pelle.sak.testing

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.pelle.sak.core.SakProcess
import io.pelle.sak.core.qr.QrCamera
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// the camera of the scan on the jvm: a QR code on a screen, the way the camera of a phone sees the web
// app, in place of the preview; the camera reads code once the test sets it
class FakeQrCamera(
    // what the QR code in the picture shows
    private val shown: String = "https://sak.example.org/connect#token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbm5hIn0.c2lnbmF0dXJl",
) : QrCamera {
    var code by mutableStateOf<String?>(null)

    // the last state of the light the scan asked for
    var torch = false
        private set

    @Composable
    override fun Preview(
        torch: Boolean,
        onCode: (String) -> Unit,
        modifier: Modifier,
    ) {
        this.torch = torch
        val read = code
        LaunchedEffect(read) { read?.let(onCode) }
        val modules = remember(shown) { QRCodeWriter().encode(shown, BarcodeFormat.QR_CODE, 0, 0) }
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF1C1F24), Color(0xFF4A4238)))), contentAlignment = Alignment.Center) {
            // a screen a little turned, its code inside the frame of the scan
            Box(
                Modifier
                    .square(0.55f)
                    .rotate(-4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF4F4F2))
                    .padding(12.dp),
            ) {
                Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
                    val side = size.minDimension / modules.width
                    for (y in 0 until modules.height) {
                        for (x in 0 until modules.width) {
                            if (modules[x, y]) drawRect(Color(0xFF111111), Offset(x * side, y * side), Size(side + 0.5f, side + 0.5f))
                        }
                    }
                }
            }
        }
    }
}

// a square of a share of the shorter side of the space it gets
private fun Modifier.square(share: Float) =
    layout { measurable, constraints ->
        val side = (minOf(constraints.maxWidth, constraints.maxHeight) * share).toInt()
        val placeable = measurable.measure(constraints.copy(minWidth = side, maxWidth = side, minHeight = side, maxHeight = side))
        layout(side, side) { placeable.place(0, 0) }
    }

// the fake camera for the scan of the app on the jvm, allowed (the user said yes) and with a light;
// the camera the app has
fun fakeCamera(allowed: Boolean = true): FakeQrCamera {
    val application = RuntimeEnvironment.getApplication()
    if (allowed) shadowOf(application).grantPermissions(Manifest.permission.CAMERA) else shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
    shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH, true)
    return FakeQrCamera().also { SakProcess.qrCamera = it }
}
