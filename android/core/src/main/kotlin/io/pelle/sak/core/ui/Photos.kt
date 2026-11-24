package io.pelle.sak.core.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.pelle.sak.core.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// takes or chooses a photo for a target of the app (e.g. an item, a note); the camera only when the
// phone has one (UX-8)
class PhotoCapture(
    val hasCamera: Boolean,
    val take: (target: Int) -> Unit,
    val choose: (target: Int) -> Unit,
)

val LocalPhotoCapture = staticCompositionLocalOf<PhotoCapture> { error("no photo capture") }

// at the root of the app, so the answer of the camera is not lost when android ended the app while
// the camera was open: the target is in the saved state. photo gets the target, read (the scaled
// jpeg, null when it can not be read; the file the camera wrote goes after it, also when it is
// cancelled) and discard (the file goes without a read), one of them called once
@Composable
fun rememberPhotoCapture(photo: (target: Int, read: suspend () -> ByteArray?, discard: () -> Unit) -> Unit): PhotoCapture {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<Int?>(null) }
    // a file of the cache, shared with the camera app by the file provider (the manifest of core)
    val cameraFile = remember { File(context.cacheDir, "photos/camera.jpg") }
    val cameraUri =
        remember {
            cameraFile.parentFile?.mkdirs()
            FileProvider.getUriForFile(context, "${context.packageName}.photos", cameraFile)
        }

    fun send(
        uri: Uri,
        fromCamera: Boolean,
    ) {
        val target = pending ?: return
        pending = null
        val read: suspend () -> ByteArray? = {
            try {
                PhotoScaling.read(context, uri)
            } finally {
                if (fromCamera) withContext(NonCancellable + Dispatchers.IO) { cameraFile.delete() }
            }
        }
        photo(target, read) { if (fromCamera) cameraFile.delete() }
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            // the file of the camera (with its exif) goes also when no photo was taken
            if (taken) {
                send(cameraUri, fromCamera = true)
            } else {
                pending = null
                cameraFile.delete()
            }
        }
    // the app declares the camera for the scan of a QR code, so android lets the camera app take a
    // photo for it only once the user allowed the camera; asked for when the user takes a photo
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) camera.launch(cameraUri) else pending = null
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(uri, fromCamera = false) else pending = null
        }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return remember(camera, permission, picker) {
        PhotoCapture(
            hasCamera,
            take = { target ->
                pending = target
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    camera.launch(cameraUri)
                } else {
                    permission.launch(Manifest.permission.CAMERA)
                }
            },
            choose = { target ->
                pending = target
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

// an image the app fetches (load, null for none), decoded off the main thread; the box shows its
// background until then. key starts the load again
@Composable
fun RemoteImage(
    key: Any?,
    background: Color,
    scale: ContentScale,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    load: suspend () -> ByteArray?,
) {
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = load()?.let { bytes -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } }
    }
    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = scale, modifier = imageModifier) }
    }
}

// a photo in full over the screen; a tap or back closes it
@Composable
fun PhotoViewer(
    close: () -> Unit,
    photo: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClickLabel = stringResource(R.string.photo_close), onClick = close)
                .testTag("photo-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            photo()
        }
    }
}
