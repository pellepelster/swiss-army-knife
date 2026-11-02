package io.pelle.sak.grocery.ui

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.ui.PhotoScaling
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.scaledSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// how large the photo of an item shows in its row, and the space around it
internal val PHOTO_IN_ROW = 48.dp
internal val PHOTO_SPACE = 8.dp

// takes a photo with the camera or chooses one of the phone for an item; whether the phone has a camera
class PhotoPicker(
    val hasCamera: Boolean,
    val take: (itemId: Int) -> Unit,
    val choose: (itemId: Int) -> Unit,
)

internal val LocalPhotoPicker = staticCompositionLocalOf<PhotoPicker> { error("no photo picker") }

// the camera and the photo picker of the app, at its root: always there, also while the app starts
// anew after android ended it while the camera was open, so their answer is never lost. The item
// they are for is kept with the saved state; the photo is read and sent by the model, which waits for
// the lists. The file the camera wrote is deleted once it was read, or when the model drops it
@Composable
internal fun rememberPhotoPicker(model: GroceryModel): PhotoPicker {
    val context = LocalContext.current
    var pendingItem by rememberSaveable { mutableStateOf<Int?>(null) }
    // the camera writes into a file of the cache, shared with it by the file provider only
    val cameraFile = remember { File(context.cacheDir, "photos/camera.jpg") }
    val cameraUri =
        remember {
            cameraFile.parentFile?.mkdirs()
            FileProvider.getUriForFile(context, "${context.packageName}.photos", cameraFile)
        }
    fun send(
        uri: Uri,
        done: () -> Unit = {},
    ) {
        val itemId = pendingItem ?: return
        pendingItem = null
        model.attachPhoto(
            itemId,
            read = {
                withContext(Dispatchers.IO) {
                    try {
                        PhotoScaling.jpeg(context, uri)
                    } catch (e: Exception) {
                        Log.w("sak-grocery", "a photo could not be read", e)
                        null
                    } finally {
                        done()
                    }
                }
            },
            discard = done,
        )
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (taken) {
                send(cameraUri) { cameraFile.delete() }
            } else {
                pendingItem = null
                cameraFile.delete()
            }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(uri) else pendingItem = null
        }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return remember(camera, picker) {
        PhotoPicker(
            hasCamera,
            take = { itemId ->
                pendingItem = itemId
                camera.launch(cameraUri)
            },
            choose = { itemId ->
                pendingItem = itemId
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

// the photo of an item as large as it shows: fetched in the scaled size that fills that many pixels
// of the screen (scaledSize), the photo itself when none is that large
@Composable
internal fun Photo(
    item: GroceryItemResponse,
    model: GroceryModel,
    showsAt: Dp,
    scale: ContentScale,
    modifier: Modifier = Modifier,
    full: Boolean = false,
) {
    val pixels = with(LocalDensity.current) { showsAt.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, item.photoId, pixels) {
        value =
            model.photo(item, scaledSize(pixels), full)?.let { bytes ->
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize()) }
    }
}

// the photo of an item in full over the list, as large as the screen; a tap or back closes it
@Composable
internal fun PhotoDialog(
    item: GroceryItemResponse,
    model: GroceryModel,
) {
    val configuration = LocalConfiguration.current
    val screen = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    Dialog(onDismissRequest = { model.viewPhoto(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClickLabel = stringResource(R.string.grocery_photo_close)) { model.viewPhoto(null) }
                .testTag("photo-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            Photo(item, model, screen, ContentScale.Fit, Modifier.fillMaxSize().padding(16.dp), full = true)
        }
    }
}
