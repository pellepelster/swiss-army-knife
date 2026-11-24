package io.pelle.sak.grocery.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.ui.PhotoCapture
import io.pelle.sak.core.ui.PhotoViewer
import io.pelle.sak.core.ui.RemoteImage
import io.pelle.sak.core.ui.rememberPhotoCapture
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.scaledSize

// how large the photo of an item shows in its row, and the space around it
internal val PHOTO_IN_ROW = 48.dp
internal val PHOTO_SPACE = 8.dp

// the target of a photo whose items the AI reads (GROCERY-16); every other target is an item id
internal const val DISCOVER = -1

// the model waits for the lists; the file the camera wrote is deleted once read, or when the model
// drops it
@Composable
internal fun rememberGroceryPhotos(model: GroceryModel): PhotoCapture =
    rememberPhotoCapture { target, read, discard ->
        if (target == DISCOVER) model.discover(discard = discard, read = read) else model.attachPhoto(target, discard = discard, read = read)
    }

// fetched in the scaled size that fills the pixels it shows at (scaledSize)
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
    RemoteImage(item.photoId to pixels, MaterialTheme.colorScheme.surfaceContainerHigh, scale, modifier) {
        model.photo(item, scaledSize(pixels), full)
    }
}

// the photo of an item in full over the list, as large as the screen
@Composable
internal fun PhotoDialog(
    item: GroceryItemResponse,
    model: GroceryModel,
) {
    val configuration = LocalConfiguration.current
    val screen = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    PhotoViewer(close = { model.viewPhoto(null) }) {
        Photo(item, model, screen, ContentScale.Fit, Modifier.fillMaxSize().padding(16.dp), full = true)
    }
}
