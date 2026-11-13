package io.pelle.sak.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.pelle.sak.core.ui.PhotoScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// what another app shares with the notes: its photos (content uris) and a text, e.g. a caption
data class Shared<T>(
    val photos: List<T>,
    val text: String,
)

// the photos and the text of a SEND or SEND_MULTIPLE; null for another intent or one without a photo.
// The type is checked again: an intent that names this activity skips the filters of the manifest
fun <T> shared(
    action: String?,
    type: String?,
    streams: List<T?>,
    text: CharSequence?,
): Shared<T>? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    if (type?.startsWith("image/") != true) return null
    val photos = streams.filterNotNull()
    if (photos.isEmpty()) return null
    return Shared(photos, text?.toString().orEmpty())
}

// the photo scaled by PhotoScaling; null when it can not be read
suspend fun scaledPhoto(
    context: Context,
    uri: Uri,
): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            PhotoScaling.jpeg(context, uri)
        } catch (e: Exception) {
            Log.w("sak-notes", "the photo $uri could not be read", e)
            null
        }
    }
