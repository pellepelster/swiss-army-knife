package io.pelle.sak.core.qr

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

// the camera of the scan: its preview, and the texts of the QR codes it sees, read on the phone
// (PRIVACY-7). The app has the camera at the back (CameraXQrCamera), the tests a fake
// (SakProcess.qrCamera)
interface QrCamera {
    // the preview, filling modifier; onCode gets the text of each picture that shows a QR code, on
    // the main thread; torch turns the light of the camera on
    @Composable
    fun Preview(
        torch: Boolean,
        onCode: (String) -> Unit,
        modifier: Modifier,
    )
}

// the camera at the back by camerax: the preview and the analysis of its pictures, bound to the
// lifecycle of the screen, so it ends with it, also on a rotation; no picture is kept
object CameraXQrCamera : QrCamera {
    @Composable
    override fun Preview(
        torch: Boolean,
        onCode: (String) -> Unit,
        modifier: Modifier,
    ) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val found by rememberUpdatedState(onCode)
        val controller =
            remember(context) {
                LifecycleCameraController(context).apply {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                    imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    // enough pixels for the dense code of a long token, little enough for each picture
                    imageAnalysisResolutionSelector =
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                }
            }
        DisposableEffect(owner, controller) {
            val analysis = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            controller.setImageAnalysisAnalyzer(analysis, QrAnalyzer { text -> main.execute { found(text) } })
            controller.bindToLifecycle(owner)
            onDispose {
                controller.unbind()
                controller.clearImageAnalysisAnalyzer()
                analysis.shutdown()
            }
        }
        // a camera without a light, or one not open yet, ignores it
        LaunchedEffect(controller, torch) { runCatching { controller.enableTorch(torch) } }
        AndroidView(
            factory = {
                PreviewView(it).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = modifier,
        )
    }
}

// the brightness of each picture (its first plane, YUV_420_888) handed to the decoder; the buffer of
// the pictures is reused
private class QrAnalyzer(
    private val found: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var bytes = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val size = plane.rowStride * it.height
            if (bytes.size != size) bytes = ByteArray(size)
            // the last row may end before its stride
            buffer.get(bytes, 0, minOf(buffer.remaining(), size))
            decoder.decode(bytes, plane.rowStride, it.width, it.height)?.let(found)
        }
    }
}
